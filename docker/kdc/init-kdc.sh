#!/bin/bash
set -e

REALM="DEMO.LOCAL"
DOMAIN="demo.local"
KDC_PASSWORD="kdc-admin-pass"

echo "=== Initializing MIT Kerberos KDC for realm $REALM ==="

# 1. Create KDC database (non-interactive)
if [ ! -f /var/lib/krb5kdc/principal ]; then
  echo "Creating KDC database..."
  master_key="master123"
  kdb5_util create -r "$REALM" -s -P "$master_key"
fi

# 2. Create admin principal
echo "Creating admin principal..."
echo -e "$KDC_PASSWORD\n$KDC_PASSWORD" | kadmin.local -q \
  "addprinc -randkey kadmin/admin@$REALM" 2>/dev/null || true

echo -e "$KDC_PASSWORD\n$KDC_PASSWORD" | kadmin.local -q \
  "addprinc kadmin/admin@$REALM" 2>/dev/null || true

# 3. Create service principal for SPNEGO
echo "Creating service principals..."
for princ in \
  "HTTP/localhost@$REALM" \
  "HTTP/demo.local@$REALM" \
  "demo-user@$REALM" \
  "demo-admin@$REALM"; do
  
  echo "  Adding: $princ"
  kadmin.local -q "addprinc -pw demoPass123 $princ" 2>/dev/null || true
done

# 4. Export keytab with all service principals
KEYTAB="/keytabs/demo.keytab"
mkdir -p /keytabs
rm -f "$KEYTAB"

echo "Exporting keytab..."
kadmin.local -q "ktadd -k $KEYTAB -norandkey HTTP/localhost@$REALM" 2>/dev/null
kadmin.local -q "ktadd -k $KEYTAB -norandkey HTTP/demo.local@$REALM" 2>/dev/null
kadmin.local -q "ktadd -k $KEYTAB -norandkey demo-user@$REALM" 2>/dev/null
kadmin.local -q "ktadd -k $KEYTAB -norandkey demo-admin@$REALM" 2>/dev/null

chmod 644 "$KEYTAB"
echo "Keytab exported to: $KEYTAB"

# Also copy krb5.conf to the shared volume for host access
cp /etc/krb5.conf /keytabs/krb5.conf

# 5. Create kadm5.acl to allow admin operations
echo "*/admin@$REALM *" > /var/lib/krb5kdc/kadm5.acl

# 6. Start KDC and admin services
mkdir -p /var/log
echo "=== Starting KDC services ==="

# Start KDC
krb5kdc -n &
KDC_PID=$!

# Start kadmind
kadmind -P /var/run/kadmind.pid &
KADMIN_PID=$!

# Wait for KDC to be ready
sleep 3

echo "=== KDC is ready ==="
echo "Realm:  $REALM"
echo "KDC:    localhost:88"
echo "Admin:  localhost:749"
echo ""
echo "Principals:"
kadmin.local -q "listprincs" 2>/dev/null | grep -v "Authenticating" | sort

echo ""
echo "Keytab contents:"
klist -kt "$KEYTAB" 2>/dev/null

# Keep container running
wait $KDC_PID
