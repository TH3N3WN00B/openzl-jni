#!/bin/sh
# Acts like a downstream project: resolves io.github.th3n3wn00b:openzl-jni (base jar +
# one native classifier) from GitHub Packages and round-trips data through it.
#
#   usage: ci/verify-consumer.sh <classifier>
#   env:   VERSION            published version to resolve (e.g. 0.3.2)
#          GITHUB_REPOSITORY  owner/repo that hosts the package
#          GITHUB_ACTOR, GITHUB_TOKEN  credentials with read:packages
#
# POSIX sh on purpose: it runs on Linux, Windows (Git Bash), macOS, FreeBSD and illumos.
set -eu

classifier="$1"
: "${VERSION:?VERSION is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
actor="${GITHUB_ACTOR:-github-actions}"

work="consumer-$classifier"
rm -rf "$work"
mkdir -p "$work"
cd "$work"

cat > settings.xml <<EOF
<settings>
  <servers>
    <server>
      <id>github-openzl-jni</id>
      <username>$actor</username>
      <password>$GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
EOF

cat > pom.xml <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>openzl-consumer</artifactId>
  <version>1.0</version>
  <repositories>
    <repository>
      <id>github-openzl-jni</id>
      <url>https://maven.pkg.github.com/$GITHUB_REPOSITORY</url>
    </repository>
  </repositories>
  <dependencies>
    <dependency>
      <groupId>io.github.th3n3wn00b</groupId>
      <artifactId>openzl-jni</artifactId>
      <version>$VERSION</version>
    </dependency>
    <dependency>
      <groupId>io.github.th3n3wn00b</groupId>
      <artifactId>openzl-jni</artifactId>
      <version>$VERSION</version>
      <classifier>$classifier</classifier>
    </dependency>
  </dependencies>
</project>
EOF

mvn -B -ntp -s settings.xml dependency:tree
mvn -B -ntp -q -s settings.xml dependency:copy-dependencies -DoutputDirectory=libs
ls -l libs
if ls libs | grep -q protobuf; then
    echo "::error::protobuf-java should be optional, but the consumer pulled it"
    exit 1
fi

cat > Check.java <<'EOF'
import io.github.hybledav.OpenZLCompressionLevel;
import io.github.hybledav.OpenZLCompressor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class Check {
    public static void main(String[] args) {
        byte[] data = "openzl-jni from GitHub Packages ".repeat(4096).getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.setCompressionLevel(OpenZLCompressionLevel.LEVEL_19);
            byte[] packed = c.compress(data);
            if (!Arrays.equals(data, c.decompress(packed))) {
                throw new AssertionError("round trip failed");
            }
            System.out.println("OK " + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                    + " " + data.length + " -> " + packed.length + " bytes");
        }
    }
}
EOF
java --enable-native-access=ALL-UNNAMED -cp "libs/*" Check.java
