# Build from the repository root with make docker-build-frontend.
# Run locally with make docker-run-frontend (port 3000 -> 8080).

# Stage 1: Install dependencies and build the application
FROM node:24.22.1-alpine AS build
WORKDIR /app
# Install Java for openapi-generator-cli
RUN apk add --no-cache openjdk25-jre
COPY package.json yarn.lock ./
RUN yarn install --frozen-lockfile \
    && yarn cache clean
COPY . .
COPY .env.production .env
RUN yarn run build:moviesGen \
    && yarn build

# Stage 2: Set up the Nginx server to serve the application
FROM nginx:1.30-alpine
# The official image already owns uid/gid 101 for nginx. Move every runtime
# write into /tmp so Kubernetes can mount the root filesystem read-only.
RUN sed -i '/^user  /d; s!^pid .*;!pid /tmp/nginx.pid;!' /etc/nginx/nginx.conf
COPY --from=build /app/build /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
