#!/bin/bash
#
# JAQ VM Startup Script
#

# install packages
apt-get update && apt-get install -y tmux htop openjdk-8-jdk-headless git rlwrap

# install clojure
if [ ! $(which clj) ]; then
    echo "Installing Clojure"
    curl https://download.clojure.org/install/linux-install-1.9.0.397.sh | bash -
fi

# add swap
if [ ! -f /swapfile ]; then
    echo "Enabling swap"
    fallocate -l 4G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
fi

# generate SSL cert
if [ ! -f /root/jaq-repl.jks ]; then
    echo "Creating SSL cert"
    keytool -keystore /root/jaq-repl.jks \
        -alias jetty \
        -keyalg RSA \
        -keysize 2048 \
        -sigalg SHA256withRSA \
        -genkey \
        -validity 3650 \
        -noprompt \
        -storepass jaqrepl \
        -keypass jaqrepl \
        -dname "CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown"
fi

# add custom attributes as env vars
METADATA_URL="metadata.google.internal/computeMetadata/v1/instance/attributes"
METADATA_HEADER="Metadata-Flavor: Google"
SKIP=( ["startup-script"]=1 ["deps.edn"]=1 )

KEYS=($(curl -s http://${METADATA_URL}/ -H 'Metadata-Flavor: Google'))
for v in "${KEYS[@]}"
do
    if [ -z "${SKIP[$v]}" ]; then
        VAL="${v}="
        VAL+="$(curl -s http://${METADATA_URL}/${v} -H 'Metadata-Flavor: Google')"
        eval "${VAL}"
    fi
done

if [ ! -f deps.edn ]; then
    echo ""

fi
