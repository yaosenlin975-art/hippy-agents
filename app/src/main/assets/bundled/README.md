# Bundled Node.js

Place `node-v22-arm64.tar.xz` here to enable offline Node.js installation.

The archive should contain the standard Node.js v22 Linux ARM64 directory structure
(bin/, lib/, include/, etc.) that can be extracted directly into the rootfs with:

```bash
tar -xJf node-v22-arm64.tar.xz -C / --strip-components=1
```

To create the archive from an existing Node.js installation:

```bash
# On a Linux ARM64 machine with Node.js v22 installed via NodeSource:
cd /
tar -cJf node-v22-arm64.tar.xz usr/bin/node usr/bin/npm usr/bin/npx \
    usr/lib/node_modules/npm usr/include/node usr/share/doc/nodejs \
    usr/share/man/man1/node.1 usr/share/systemd/system/nodesource.service \
    etc/apt/sources.list.d/nodesource.list
```

File size: ~25-30MB compressed.
