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
  CMD ["node", "-e", "const fs=require('fs'),https=require('https'),http=require('http');const tls=fs.existsSync(process.env.MUNCHKIN_TLS_KEY||'key.pem')&&fs.existsSync(process.env.MUNCHKIN_TLS_CERT||'cert.pem');const mod=tls?https:http;const req=mod.request({host:'127.0.0.1',port:process.env.PORT||8765,path:'/health',rejectUnauthorized:false},res=>process.exit(res.statusCode===200?0:1));req.on('error',()=>process.exit(1));req.end();"]

CMD ["node", "server.js"]
