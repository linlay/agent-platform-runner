---
name: "container_hub_validation"
description: "Container Hub RUN 沙箱验证清单：先 Bash，再用容器内 Python 写文件。"
---

# Container Hub Validation

Use this skill when the task is to verify the `container_hub_bash` tool and the RUN-level sandbox flow.

Rules:

1. All command execution must go through `container_hub_bash`.
2. Do not use `_bash_`, MCP tools, or any host-side execution path for this validation.
3. Treat `python3` as a container dependency check. If missing, report it as an environment gap.
4. Do not claim host-side files were written unless the tool output proves it or the configured mount behavior clearly implies it.

## Phase 1: Bash Smoke

Run the checks in this order when the user asks to validate the sandbox:

1. `pwd`
2. `ls /workspace`
3. `echo hello > /workspace/bash_ok.txt`
4. `cat /workspace/bash_ok.txt`

Phase 1 passes only if:

- commands return `exitCode: 0`
- `/workspace/bash_ok.txt` can be read back with the expected content

## Phase 2: Python Write

After Bash passes, validate container-side Python by running `python3` through `container_hub_bash`.

Preferred outcome:

- write `/workspace/validation_report.txt`
- include a short report body describing the validation timestamp or stage summary
- read the file back in a follow-up command

Suggested command shape:

- `python3 -c "from pathlib import Path; Path('/workspace/validation_report.txt').write_text(...)"`  
- then `cat /workspace/validation_report.txt`

If the environment is configured with container hub `data-dir` mount, explain that `/workspace/<file>` is expected to map back to `data/<chatId>/<file>` on the host for RUN-level sessions.

## Final Answer Format

Use this structure:

- `executed:` which phases and commands were run
- `result:` pass/fail by phase
- `artifacts:` container paths and any expected host mapping
- `blockers:` missing dependency, missing config, or `none`
