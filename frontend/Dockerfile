# syntax=docker/dockerfile:1
# === Stage 1: Build Vue 3 + Vite SPA (với BuildKit npm cache) ===
FROM node:20-alpine AS build
WORKDIR /app

# Copy package manifests first to leverage Docker layer caching + npm cache mount
COPY package*.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm install

# Copy frontend source and build production bundle
COPY . .
RUN npm run build

# === Stage 2: Production Nginx Reverse Proxy + Static Server ===
FROM nginx:1.27-alpine

# Copy custom Nginx config (SPA fallback + /api reverse proxy + 100MB upload limit)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy compiled static assets from Stage 1
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
