FROM node:22-alpine
ENV NODE_ENV=production HOST=0.0.0.0 PORT=8787 VOICX_STORE=/data/voicx-store.json
WORKDIR /app
COPY package.json server.mjs ./
RUN mkdir -p /data && chown -R node:node /app /data
USER node
EXPOSE 8787
VOLUME ["/data"]
CMD ["node", "server.mjs"]
