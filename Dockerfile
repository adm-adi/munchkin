FROM node:20-bookworm-slim

WORKDIR /app/server

COPY server/package*.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY server/ ./

RUN mkdir -p /var/lib/munchkin /app/server/logs \
    && chown -R node:node /var/lib/munchkin /app/server

USER node

ENV NODE_ENV=production \
    PORT=8765 \
    MUNCHKIN_DB_PATH=/var/lib/munchkin/munchkin.db

EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD ["node", "-e", "fetch(`http://127.0.0.1:${process.env.PORT || 8765}/health`).then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"]

CMD ["node", "server.js"]
