#!/usr/bin/env bash
# ==============================================================================
# DocMind — 1-Command Ubuntu / Linux Docker Deployment & Dev Automation Script
# Usage:
#   ./deploy.sh           # Build & start full stack + tự động cấp link HTTPS công khai
#   ./deploy.sh update    # git pull + chỉ build lại đúng service vừa thay đổi (siêu nhanh)
#   ./deploy.sh url       # Xem ngay link HTTPS công khai (Cloudflare SSL) & link IP nội bộ
#   ./deploy.sh restart   # Nạp lại .env (API Key / Model) & khởi động lại nhanh
#   ./deploy.sh logs      # Xem log trực tiếp
#   ./deploy.sh status    # Kiểm tra trạng thái các container
#   ./deploy.sh down      # Tắt hệ thống (giữ nguyên dữ liệu DB)
#   ./deploy.sh clean     # Xóa sạch container & DB volume để làm mới từ đầu
# ==============================================================================
set -euo pipefail

cd "$(dirname "$0")"

export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1

ACTION="${1:-up}"

ensure_env_file() {
  # Fix accidental directory created by Docker if compose was run before .env existed
  if [ -d ".env" ]; then
    echo "⚠️  Phát hiện .env là thư mục rỗng do Docker tạo nhầm, đang xóa..."
    rm -rf .env
  fi

  if [ ! -f ".env" ]; then
    echo "📝 Chưa có file .env — đang tự động khởi tạo từ .env.example..."
    cp .env.example .env
    echo "✅ Đã tạo file .env mặc định. (Bạn có thể sửa LLM_PROVIDER / LLM_API_KEY trong .env bất kỳ lúc nào)"
  fi
}

wait_for_health() {
  echo "⏳ Đang chờ các dịch vụ khởi động và vượt qua Healthcheck (tối đa 60s)..."
  local retries=30
  local count=0
  while [ $count -lt $retries ]; do
    if curl -fsS http://127.0.0.1:8090/api/health >/dev/null 2>&1 && \
       curl -fsS http://127.0.0.1:${FRONTEND_PORT:-3000}/ >/dev/null 2>&1; then
      echo "✅ Tất cả dịch vụ (Frontend + Java API + Python Parser + Postgres) đã SẴN SÀNG!"
      return 0
    fi
    count=$((count + 1))
    sleep 2
  done
  echo "⚠️  Quá thời gian chờ healthcheck tự động, kiểm tra trạng thái bằng: ./deploy.sh logs"
}

get_tunnel_url() {
  local retries=12
  local count=0
  local url=""
  while [ $count -lt $retries ]; do
    url=$(docker logs docmind-tunnel 2>&1 | grep -oE 'https://[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)*\.trycloudflare\.com' | tail -n 1 || true)
    if [ -n "$url" ]; then
      echo "$url"
      return 0
    fi
    count=$((count + 1))
    sleep 1
  done
  echo ""
}

print_summary() {
  local host_ip
  host_ip=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "localhost")
  [ -z "$host_ip" ] && host_ip="localhost"
  local fe_port="${FRONTEND_PORT:-3000}"
  local https_url
  https_url=$(get_tunnel_url)

  echo ""
  echo "=========================================================================="
  echo "🚀 DOCMIND FULL-STACK ĐÃ CHẠY THÀNH CÔNG TRÊN DOCKER!"
  echo "=========================================================================="
  docker compose ps
  echo "--------------------------------------------------------------------------"
  if [ -n "$https_url" ]; then
    echo "🔒 LINK HTTPS CÔNG KHAI (Mở trên Chrome mọi nơi / Gắn Facebook Webhook):"
    echo "   👉 ${https_url}"
    echo "   👉 Webhook API: ${https_url}/api/bot/fanpage/message"
    echo "--------------------------------------------------------------------------"
  else
    echo "⏳ Link HTTPS Cloudflare Tunnel đang khởi tạo (gõ './deploy.sh url' sau 5s để xem)"
    echo "--------------------------------------------------------------------------"
  fi
  echo "🌐 Giao diện Web qua IP Server   : http://${host_ip}:${fe_port}"
  echo "🔌 Backend API (Spring Boot 4)   : http://${host_ip}:8090/api/health"
  echo "🐍 Doc Parser (Python FastAPI)   : http://${host_ip}:8091/healthz"
  echo "🐘 PostgreSQL 16 + pgvector      : ${host_ip}:5433 (user/db: docmind)"
  echo "--------------------------------------------------------------------------"
  echo "💡 Lệnh vận hành nhanh cho Dev:"
  echo "   • Kéo code mới & chỉ build phần sửa : ./deploy.sh update"
  echo "   • Xem lại link HTTPS công khai      : ./deploy.sh url"
  echo "   • Đổi API Key trong .env & nạp ngay : ./deploy.sh restart"
  echo "   • Xem log trực tiếp                 : ./deploy.sh logs"
  echo "=========================================================================="
}

smart_update() {
  ensure_env_file
  local before_head=""
  if git rev-parse HEAD >/dev/null 2>&1; then
    before_head=$(git rev-parse HEAD)
    echo "📥 Đang kéo code mới nhất từ Git (git pull)..."
    git pull --ff-only || true
  fi
  local after_head=""
  if git rev-parse HEAD >/dev/null 2>&1; then
    after_head=$(git rev-parse HEAD)
  fi

  if [ -n "$before_head" ] && [ -n "$after_head" ] && [ "$before_head" != "$after_head" ]; then
    local changed_files
    changed_files=$(git diff --name-only "$before_head" "$after_head" || true)
    local services_to_build=()

    if echo "$changed_files" | grep -qE '(^|/)frontend/'; then
      services_to_build+=("frontend")
    fi
    if echo "$changed_files" | grep -qE '(^|/)api-java/'; then
      services_to_build+=("api-java")
    fi
    if echo "$changed_files" | grep -qE '(^|/)doc-service-py/'; then
      services_to_build+=("doc-service-py")
    fi

    if [ ${#services_to_build[@]} -gt 0 ]; then
      echo "⚡ Phát hiện thay đổi tại: ${services_to_build[*]} -> Chỉ build lại đúng service này với BuildKit Cache!"
      docker compose up -d --build "${services_to_build[@]}"
    else
      echo "✅ Không có thay đổi mã nguồn ở frontend/api-java/doc-service-py, đang đồng bộ container..."
      docker compose up -d
    fi
  else
    echo "🔨 Đang cập nhật các container với BuildKit Cache..."
    docker compose up -d --build
  fi

  wait_for_health
  print_summary
}

case "$ACTION" in
  up|start|build)
    ensure_env_file
    echo "🔨 Đang build & khởi chạy toàn bộ hệ thống (Postgres, Python, Java, Frontend + HTTPS Tunnel)..."
    docker compose up -d --build --remove-orphans
    wait_for_health
    print_summary
    ;;
  update|pull)
    smart_update
    ;;
  fe|frontend)
    echo "⚡ Đang build & cập nhật riêng giao diện Frontend..."
    docker compose up -d --build frontend
    print_summary
    ;;
  be|backend|java)
    ensure_env_file
    echo "⚡ Đang build & cập nhật riêng Backend Java API..."
    docker compose up -d --build api-java
    wait_for_health
    print_summary
    ;;
  url|https|tunnel)
    print_summary
    ;;
  restart|reload)
    ensure_env_file
    echo "🔄 Đang nạp lại .env và khởi động lại các dịch vụ..."
    docker compose restart api-java doc-service-py frontend
    wait_for_health
    print_summary
    ;;
  logs)
    docker compose logs -f --tail=100
    ;;
  status|ps)
    docker compose ps
    ;;
  down|stop)
    echo "🛑 Đang dừng các container DocMind (dữ liệu Postgres vẫn được giữ an toàn)..."
    docker compose down
    ;;
  clean|reset)
    echo "🧹 Đang dừng container và xóa toàn bộ volume dữ liệu..."
    docker compose down -v --remove-orphans
    ;;
  *)
    echo "Cách dùng: ./deploy.sh {up|update|fe|be|url|restart|logs|status|down|clean}"
    exit 1
    ;;
esac
