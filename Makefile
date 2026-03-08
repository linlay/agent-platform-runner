.PHONY: build run test docker-build docker-up docker-down clean

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

clean:
	rm -rf ./target
