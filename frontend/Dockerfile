# Multi-stage build: SvelteKit static build → nginx serves it.
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json* ./
# Use --no-audit and --no-fund to skip slow network metadata fetches.
RUN npm install --no-audit --no-fund

COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/build /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
