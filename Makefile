VERSION := $(shell cat VERSION 2>/dev/null || echo "dev")
ARCH := $(shell uname -m | sed 's/x86_64/amd64/' | sed 's/aarch64/arm64/')

.PHONY: build run test docker-build docker-up docker-down release clean

build:
	mvn -DskipTests package

run:
	set -a; [ ! -f .env ] || . ./.env; set +a; SERVER_PORT="$${HOST_PORT:-$${SERVER_PORT:-8080}}" mvn spring-boot:run

test:
	mvn test

docker-build:
	docker compose build

docker-up:
	docker compose up -d --build

docker-down:
	docker compose down

release:
	VERSION=$(VERSION) ARCH=$(ARCH) bash scripts/release.sh

clean:
	rm -rf ./target
