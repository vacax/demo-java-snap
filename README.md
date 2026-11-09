# Demo Java a Snap

Proyecto de la asignatura **ICC-331 Sistemas Operativos (PUCMM)** que empaqueta una
aplicación Java como **snap** con confinamiento `strict`. Es el complemento de
[demo-java-deb](https://github.com/vacax/demo-java-deb): misma idea de aplicación,
otro modelo de empaquetado.

El snap contiene:

| Elemento | Qué muestra |
|---|---|
| Runtime Java propio creado con `jlink` (≈40 MB) | el snap lleva sus dependencias, no usa el Java del sistema |
| App `demo-java-snap` (comando) | el entorno que prepara snapd y el efecto de los **plugs** `home` y `network` |
| App `demo-java-snap.servidor` (`daemon: simple`) | un servicio systemd generado por snapd, plug `network-bind` |
| Hook `install` | define la configuración por defecto (`puerto=8085`) |
| Hook `configure` | valida `snap set` y reinicia el servicio |

## Estructura

```
snap/
├── snapcraft.yaml          receta de construcción (metadatos, apps, plugs, parts)
├── hooks/
│   ├── install             snapctl set puerto=8085 ...
│   └── configure           valida el puerto y reinicia el daemon
└── local/bin/
    ├── demo-java-snap      lanzador: $SNAP/runtime/bin/java -jar $SNAP/app/demo-java-snap.jar
    └── servidor            lanzador del daemon: lee snapctl get puerto/mensaje
src/main/java/edu/pucmm/eict/Main.java
```

## Construir

**Opción 1: Snapcraft con LXD** (forma recomendada; construye en un contenedor limpio)

```bash
sudo snap install snapcraft --classic
sudo snap install lxd && sudo lxd init --auto
sudo usermod -aG lxd $USER && newgrp lxd
snapcraft pack            # en versiones anteriores a snapcraft 8: snapcraft
```

**Opción 2: Docker con la imagen oficial de Canonical** (no requiere instalar snapcraft)

```bash
docker run --rm -v "$PWD":/project -w /project \
    ghcr.io/canonical/snapcraft:8_core24 pack --destructive-mode
sudo chown $USER: demo-java-snap_*.snap
```

Resultado: `demo-java-snap_1.0.0_amd64.snap`. Para revisarlo sin instalarlo:

```bash
unsquashfs -l demo-java-snap_1.0.0_amd64.snap | head -30   # es un sistema de archivos squashfs
unsquashfs -cat demo-java-snap_1.0.0_amd64.snap meta/snap.yaml
```

## Instalar y probar

`--dangerous` es necesario porque el paquete no viene firmado por la Snap Store.

```bash
sudo snap install --dangerous ./demo-java-snap_1.0.0_amd64.snap

demo-java-snap                       # muestra SNAP, SNAP_DATA, SNAP_USER_DATA, HOME...
snap connections demo-java-snap      # plugs conectados
snap services demo-java-snap         # estado del daemon
curl http://localhost:8085/
snap logs -f demo-java-snap.servidor
systemctl status snap.demo-java-snap.servidor.service
```

### Experimentos con el confinamiento

```bash
echo "secreto" > ~/prueba.txt
demo-java-snap leer ~/prueba.txt     # OK: plug home conectado
demo-java-snap leer ~/.bashrc        # DENEGADO: home no cubre archivos ocultos
demo-java-snap leer /var/log/dpkg.log # DENEGADO: haría falta el plug log-observe
head -1 /var/log/dpkg.log            # fuera del snap el mismo usuario sí puede leerlo

sudo snap disconnect demo-java-snap:home
demo-java-snap leer ~/prueba.txt     # ahora DENEGADO
sudo snap connect demo-java-snap:home

demo-java-snap red https://example.com
sudo snap disconnect demo-java-snap:network
demo-java-snap red https://example.com   # falla
sudo snap connect demo-java-snap:network

sudo journalctl -k | grep 'apparmor="DENIED"' | grep demo-java-snap | tail
```

### Configuración y hooks

```bash
snap get demo-java-snap
sudo snap set demo-java-snap puerto=9090 mensaje="Hola ICC-331"
curl http://localhost:9090/
sudo snap set demo-java-snap puerto=80       # rechazado por el hook configure
sudo snap set demo-java-snap puerto=abc      # rechazado
```

### Revisiones y reversión

Cada instalación crea una **revisión** nueva (`x1`, `x2`...). `SNAP_DATA`
(`/var/snap/demo-java-snap/<revisión>`) se copia en cada actualización, por lo que
`snap revert` también devuelve los datos.

```bash
# cambie version: en snapcraft.yaml, vuelva a construir e instale
sudo snap install --dangerous ./demo-java-snap_1.1.0_amd64.snap
snap list --all demo-java-snap
ls /var/snap/demo-java-snap/
sudo snap revert demo-java-snap
```

### Desinstalar

```bash
sudo snap remove demo-java-snap           # guarda un snapshot de los datos
sudo snap remove --purge demo-java-snap   # sin snapshot
snap saved
```

## Detalles técnicos que vale la pena notar

- `jlink` solo incluye los módulos usados (`java.base`, `java.net.http`, `jdk.httpserver`,
  `jdk.crypto.ec`): el runtime pasa de ~200 MB (JDK) a ~40 MB.
- En Ubuntu `lib/security/cacerts` del JDK es un enlace a `/etc/ssl/certs/java/cacerts`,
  ruta que no existe dentro del snap; por eso `override-build` copia el archivo real.
  Sin ese paso HTTPS falla con `PKIX path building failed`.
- `$SNAP` es de **solo lectura** (squashfs montado). Lo que la aplicación escriba va a
  `SNAP_DATA` (daemon, root) o `SNAP_USER_DATA` (usuario).
- Dentro del snap `HOME` apunta a `~/snap/demo-java-snap/<revisión>`; el home real
  está en `SNAP_REAL_HOME`. Java toma `user.home` de `/etc/passwd`, por eso no coincide con `HOME`.
