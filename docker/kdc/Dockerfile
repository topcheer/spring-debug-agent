FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        krb5-kdc \
        krb5-admin-server \
        krb5-user \
    && rm -rf /var/lib/apt/lists/*

# krb5 config — realm DEMO.LOCAL, KDC = this container
COPY krb5.conf /etc/krb5.conf

# Init script: create realm, principals, export keytabs
COPY init-kdc.sh /init-kdc.sh
RUN chmod +x /init-kdc.sh

# Shared volume for keytab output
VOLUME ["/keytabs"]

EXPOSE 88/udp 88/tcp 749/tcp

CMD ["/init-kdc.sh"]
