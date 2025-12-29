# Munchkin Server - Hetzner Deployment

## Archivos
- `server.js` - Servidor WebSocket
- `package.json` - Dependencias
- `setup.sh` - Script de instalación

## Despliegue

### 1. Subir archivos al servidor
```bash
scp -r server/* root@23.88.48.58:/opt/munchkin-server/
```

### 2. SSH al servidor
```bash
ssh root@23.88.48.58
cd /opt/munchkin-server
```

### 3. Ejecutar setup
```bash
chmod +x setup.sh
bash setup.sh
```

## Comandos útiles
```bash
# Ver estado
systemctl status munchkin

# Ver logs en tiempo real
journalctl -u munchkin -f

# Reiniciar
systemctl restart munchkin

# Parar
systemctl stop munchkin
```

## URL del servidor
```
ws://23.88.48.58:8765
```
