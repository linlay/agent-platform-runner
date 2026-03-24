# 版本化离线打包方案

## 1. 目标与边界

这套方案的目标，是把 `agent-platform-runner` 产出成一个带明确版本号、单目标架构、可离线部署的 release bundle，方便上传到 GitHub Release、自建制品库或内网服务器，再由部署端直接解压运行。

它解决的是“如何交付可运行版本”，不是“如何分发源码”：

- 交付物是最终 bundle，而不是源码压缩包。
- bundle 内包含预构建镜像和最小部署资产，部署端不需要源码构建环境。
- 每次构建只产出一个目标架构 bundle，不做多架构合包。

当前仓库的版本单一来源是根目录 `VERSION` 文件，正式版本格式固定为 `vX.Y.Z`。发布脚本 `scripts/release.sh` 会直接校验这个格式。以当前版本 `v0.1.0` 为例，最终产物命名规则为：

- `agent-platform-runner-v0.1.0-linux-arm64.tar.gz`
- `agent-platform-runner-v0.1.0-linux-amd64.tar.gz`

## 2. 方案总览

从可复用的角度看，这套方案拆成四层：

1. 版本层：用 `VERSION` 统一管理发布版本号。
2. 构建层：按目标架构构建 release 镜像。
3. 组装层：把镜像 tar、compose 文件、启动脚本、配置模板和 `.env.example` 组装成离线目录。
4. 交付层：把离线目录压成最终 bundle，输出到固定产物目录。

当前项目中，上面四层分别落在这些位置：

- 版本来源：`VERSION`
- 构建入口：`make release` / `scripts/release.sh`
- 模板资产：`scripts/release-assets/`
- 最终产物目录：`dist/release/`

## 3. 本项目怎么打包

### 3.1 打包入口

一步式正式发布入口：

```bash
make release
```

`Makefile` 会把 `VERSION` 和 `ARCH` 传给 `scripts/release.sh`：

```bash
VERSION=$(VERSION) ARCH=$(ARCH) bash scripts/release.sh
```

也可以直接执行：

```bash
bash scripts/release.sh
```

常见用法：

```bash
make release
ARCH=arm64 make release
ARCH=amd64 make release
```

其中：

- `VERSION` 默认读取根目录 `VERSION`
- `ARCH` 未显式传入时，会按 `uname -m` 自动识别为 `amd64` 或 `arm64`
- 脚本内部会把 `ARCH` 转成 `linux/<arch>` 作为 Docker buildx 的目标平台
- 正常发布流程是先更新根目录 `VERSION`，再执行 `make release`

### 3.2 打包输入

`scripts/release.sh` 的主要输入包括：

- 版本号：`VERSION` 文件或环境变量 `VERSION`
- 目标架构：环境变量 `ARCH` 或当前机器架构
- 宿主机构建入口：`mvn -DskipTests clean package`
- release 运行时镜像定义：`scripts/release-assets/Dockerfile.release`
- release 基础镜像：环境变量 `RELEASE_BASE_IMAGE`，默认 `eclipse-temurin:21-jre-jammy`
- release 本地基础镜像兜底：环境变量 `RELEASE_BASE_IMAGE_LOCAL`
- release 模板资产：`scripts/release-assets/compose.release.yml`
- release 模板资产：`scripts/release-assets/start.sh`
- release 模板资产：`scripts/release-assets/stop.sh`
- release 模板资产：`scripts/release-assets/README.txt`
- release 环境模板：`scripts/release-assets/.env.example`
- 宿主机构建产物：`target/*.jar`
- 配置模板：`configs/*.example.yml`
- 配置模板：`configs/**/*.example.*`
- 运行时目录约定：由 `.env` 中的 `*_DIR` 指向宿主机路径，其中四类动态注册目录默认回落到 `./runtime/registries/providers`、`./runtime/registries/models`、`./runtime/registries/mcp-servers`、`./runtime/registries/viewport-servers`，其余目录默认回落到 `./runtime/owner`、`./runtime/agents`、`./runtime/teams`、`./runtime/root`、`./runtime/schedules`、`./runtime/chats`、`./runtime/pan`、`./runtime/skills-market`；所有这些 `*_DIR` 都可以改成绝对宿主机路径
- release compose 会把根目录 `.env` 只读挂载到 `/tmp/runner-host.env`，并通过 `SANDBOX_HOST_DIRS_FILE` 指向这份 mapping 文件，供 runner 在创建 sandbox mount 时读取宿主机路径
- release compose 会显式设置 `SPRING_PROFILES_ACTIVE=docker`，应用在容器内固定读取 `/opt/agents`、`/opt/chats`、`/opt/root` 以及 `/opt/registries/{providers,models,mcp-servers,viewport-servers}` 等目录；`.env` 里的 `*_DIR` 只负责宿主机 bind mount source

脚本会强校验版本格式：

- 只接受 `vX.Y.Z`
- 不符合时直接失败，不继续构建

### 3.3 构建过程

打包脚本会先在宿主机执行：

```bash
mvn -DskipTests clean package
```

然后校验 `target/` 下恰好存在一个可运行 jar，过滤掉 `*.jar.original` 之类非最终产物，再构建一个 release 镜像：

- `agent-platform-runner:<VERSION>`

对应命令由 `docker buildx build` 完成，并直接导出为 Docker 镜像 tar：

```bash
docker buildx build \
  --platform "linux/$ARCH" \
  --file scripts/release-assets/Dockerfile.release \
  --build-arg "BASE_IMAGE=<base-image>" \
  --build-arg "APP_JAR=target/<jar-name>.jar" \
  --tag "agent-platform-runner:$VERSION" \
  --output "type=docker,dest=.../agent-platform-runner.tar" \
  .
```

这样 release 不再在 `docker buildx` 内执行 Maven 依赖下载和编译，而是直接复用宿主机 Maven 的网络、代理和本地缓存。`Dockerfile.release` 只负责把宿主机构建出来的 jar 封装进运行镜像。

若官方镜像 `eclipse-temurin:21-jre-jammy` 在当前网络下拉取缓慢，不要默认只重试某一个国内镜像站。先从候选镜像站里任选一个验证 `docker pull` 是否成功。

```bash
docker pull <candidate-image>
docker tag <candidate-image> agent-platform-runner-base:jre21
RELEASE_BASE_IMAGE_LOCAL=agent-platform-runner-base:jre21 ARCH=arm64 make release
```

候选完整镜像引用示例：

```bash
m.daocloud.io/docker.io/library/eclipse-temurin:21-jre-jammy
docker.1ms.run/library/eclipse-temurin:21-jre-jammy
registry.dockermirror.com/library/eclipse-temurin:21-jre-jammy
```

规则如下：

- 以上只是候选示例，不保证任一镜像站长期可用
- 以你当前网络下 `docker pull <candidate-image>` 能成功为准
- 如果 `docker pull` 失败，不要继续 `docker tag`，直接切换下一个候选
- 本地兜底只是为了绕开 buildx 在构建过程中直接拉 blob，不是为了修复镜像站本身不可用
- 本地基础镜像必须和目标构建架构一致；`ARCH=arm64` 时必须准备 `linux/arm64` 镜像，`ARCH=amd64` 时必须准备 `linux/amd64` 镜像
- `docker load` 成功不代表架构正确；若日志出现 `InvalidBaseImagePlatform ... pulled with platform "linux/amd64", expected "linux/arm64"`，说明当前导入的是错误架构镜像

如果某个候选镜像地址已经验证可拉成功，也可以直接替换远端镜像地址：

```bash
RELEASE_BASE_IMAGE=<candidate-image> ARCH=arm64 make release
```

若同时设置 `RELEASE_BASE_IMAGE_LOCAL` 和 `RELEASE_BASE_IMAGE`，脚本优先使用 `RELEASE_BASE_IMAGE_LOCAL`。

### 3.4 组装过程

镜像构建完成后，脚本会在临时目录组装一个标准离线目录 `agent-platform-runner/`，然后拷入：

- `images/agent-platform-runner.tar`
- `compose.release.yml`
- `start.sh`
- `stop.sh`
- `README.txt`
- `.env.example`
- `configs/` 下全部可安全分发的 `*.example.*` 模板

脚本不会在 bundle 组装阶段预创建 `runtime/` 目录。宿主机上的运行时目录仍由 `.env` 里的 `*_DIR` 变量决定；如果没有覆盖，则默认回落到 `./runtime/*`。部署端首次执行 `./start.sh` 时，脚本会对最终生效的这些目录逐一执行 `mkdir -p`，因此无需在解压产物里提前塞入空目录骨架。若启用 `sandbox_bash` 且 Container Hub 运行在宿主机上，`.env` 中这些 `*_DIR` 应写成宿主机可直接访问的真实路径。

同时脚本会把 bundle 内 `.env.example` 的 `RUNNER_VERSION` 替换成当前构建版本，保证部署端复制后默认镜像标签和 bundle 内镜像一致。

### 3.5 最终输出

最后一步会把整个 `agent-platform-runner/` 目录压缩成：

```text
dist/release/agent-platform-runner-vX.Y.Z-linux-<arch>.tar.gz
```

这就是对外分发的正式交付物。

## 4. 打哪些包，产物分别在哪里

### 4.1 镜像层产物

镜像层产物存在于 bundle 的 `images/` 目录中：

- `images/agent-platform-runner.tar`

它不是最终对外分发文件，但它是 bundle 的核心内容。部署端如果本机还没有对应标签的镜像，`start.sh` 会自动从这里执行 `docker load`。

### 4.2 交付层产物

交付层产物只有一个，就是最终离线 bundle：

- `dist/release/agent-platform-runner-vX.Y.Z-linux-arm64.tar.gz`
- `dist/release/agent-platform-runner-vX.Y.Z-linux-amd64.tar.gz`

注意：

- 每次构建只会产出其中一个架构包
- `dist/release/` 是固定输出目录，适合做上传、归档和校验入口
- `dist/` 已被 `.gitignore` 忽略，不进入版本库

### 4.3 bundle 解压后的运行时结构

bundle 解压后，目录大致如下：

```text
agent-platform-runner/
  .env.example
  compose.release.yml
  start.sh
  stop.sh
  README.txt
  images/
    agent-platform-runner.tar
  configs/
    *.example.yml
    *.example.pem
```

其中 `SKILLS_MARKET_DIR` 与 `SCHEDULES_DIR` 默认为 `./runtime/...`，也可以在 `.env` 中覆盖为任意宿主机路径；`./start.sh` 会自动创建最终生效的目录，因此解压后不需要先看到 `runtime/` 骨架。

部署启动后，还会在本地生成或补充：

- `.env`：由使用者从 `.env.example` 复制并填入真实配置
- `configs/*.yml` / `configs/*.pem`：由使用者从发布包内的 `*.example.*` 模板复制出的真实配置
- `runtime/*` 或 `.env` 指向的其他宿主机目录：由 `./start.sh` 按需自动创建，业务目录和运行数据由部署端外部提供或自行填充

## 5. 部署端如何消费这些包

标准步骤：

```bash
tar -xzf agent-platform-runner-v0.1.0-linux-amd64.tar.gz
cd agent-platform-runner
cp .env.example .env
# 按需复制 configs 下的 *.example.* 模板为真实配置
docker network create zenmind-network   # 仅在网络尚不存在时执行
./start.sh
```

说明：

- `start.sh` 会校验 `.env`、Docker、`docker compose` 和外部网络 `zenmind-network`
- 若本机没有 `agent-platform-runner:$RUNNER_VERSION`，脚本会自动从 `images/agent-platform-runner.tar` 执行 `docker load`
- 若 `*_DIR` 指向的宿主机目录不存在，脚本会在 `docker compose up -d` 之前自动创建，包括新的 `OWNER_DIR`
- 容器内应用目录固定为 `/opt/*`；`*_DIR` 不再直接决定容器内 Spring 读取路径
- release compose 继续接入 `zenmind-network`，与现有容器互联模型保持一致

停止命令：

```bash
./stop.sh
```

## 6. 仓库内源码部署与 release bundle 的关系

当前仓库同时保留两条运维入口：

- 仓库根目录 `compose.yml`：适合源码仓库内直接部署或本地联调
- `make release` 产出的离线 bundle：适合交付制品、上传制品库或在部署机解压运行

两套入口共用同一套应用配置约定，但职责不同：

- 根目录 compose 使用 `build:`，默认仍可走容器内源码构建
- release 在宿主机先执行 Maven 打包，再使用 `image:` 导向的离线部署资产
- 根目录 `.env.example` 允许使用本机已有目录
- release bundle 的 `.env.example` 默认使用相对路径 `./runtime/*`
