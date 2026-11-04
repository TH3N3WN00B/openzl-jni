#!/bin/sh
# Installs Apache Maven from the official binary distribution (checksum verified) for
# systems without a Maven package (OmniOS/illumos). Links `mvn` into /usr/bin.
set -eu

version="${1:-3.9.16}"
base="https://archive.apache.org/dist/maven/maven-3/$version/binaries/apache-maven-$version-bin.tar.gz"

curl -fsSL -o /tmp/maven.tgz "$base"
curl -fsSL -o /tmp/maven.tgz.sha512 "$base.sha512"
expected=$(cut -d' ' -f1 /tmp/maven.tgz.sha512)
if command -v digest >/dev/null 2>&1; then
    actual=$(digest -a sha512 /tmp/maven.tgz)
else
    actual=$(sha512sum /tmp/maven.tgz | cut -d' ' -f1)
fi
if [ "$actual" != "$expected" ]; then
    echo "Maven $version checksum mismatch" >&2
    exit 1
fi

mkdir -p /opt/maven
gzip -dc /tmp/maven.tgz | (cd /opt/maven && tar xf -)
ln -sf "/opt/maven/apache-maven-$version/bin/mvn" /usr/bin/mvn
mvn -v | head -n 1
