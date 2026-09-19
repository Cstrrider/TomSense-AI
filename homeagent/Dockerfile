# The single residual home container (spec §7).
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json tsconfig.json ./
RUN npm ci
COPY src ./src
RUN npm run build

FROM node:22-alpine
WORKDIR /app
# The docker CLI is the only privileged dependency. The agent talks to the
# daemon through a mounted socket; see docker-compose.homeagent.yml for the
# read-only-vs-control trade-off note.
RUN apk add --no-cache docker-cli
COPY package*.json ./
RUN npm ci --omit=dev
COPY --from=build /app/dist ./dist

# No EXPOSE. This container never accepts an inbound connection — it only
# dials out to the HomeLink Durable Object.
USER node
CMD ["node", "dist/index.js"]
