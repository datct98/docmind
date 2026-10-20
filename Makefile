# ==============================================================================
# DocMind — Makefile cho Ubuntu / Linux Docker
# Chạy 1 lệnh duy nhất: `make` (hoặc `make up`)
# ==============================================================================

export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1

.PHONY: up update fe be url restart logs ps down clean env

# Mặc định gõ `make` sẽ chạy ./deploy.sh up (tự tạo .env + build 4 service + cấp link HTTPS)
up:
	chmod +x deploy.sh && ./deploy.sh up

# Kéo code mới từ git và chỉ build lại đúng service vừa sửa
update:
	chmod +x deploy.sh && ./deploy.sh update

# Chỉ build lại Frontend (5 giây)
fe:
	chmod +x deploy.sh && ./deploy.sh fe

# Chỉ build lại Backend Java
be:
	chmod +x deploy.sh && ./deploy.sh be

# Xem link HTTPS công khai (Cloudflare SSL)
url:
	chmod +x deploy.sh && ./deploy.sh url

restart:
	chmod +x deploy.sh && ./deploy.sh restart

logs:
	docker compose logs -f --tail=100

ps:
	docker compose ps

down:
	docker compose down

clean:
	docker compose down -v --remove-orphans
