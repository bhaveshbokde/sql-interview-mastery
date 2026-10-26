# The frontend is static files. nginx serves them and proxies /api to the
# backend, so the browser sees one origin and CORS never enters into it.

# ------------------------------------------------------------- build ----
FROM node:22-alpine AS build
WORKDIR /build

# npm ci rather than npm install: it installs exactly the lockfile and fails
# if package.json and the lock have drifted, which is what you want in a
# build and not what you want on a laptop.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ------------------------------------------------------------ runtime ----
FROM nginx:1.27-alpine AS runtime

COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
