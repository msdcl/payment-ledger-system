#!/bin/bash

# Test JDBC connection to PostgreSQL
# This helps verify the connection string works

echo "Testing PostgreSQL connection..."
echo ""

# Check if PostgreSQL container is running
CONTAINER_NAME=$(docker ps --format "{{.Names}}" | grep -i postgres | head -n 1)

if [ -z "$CONTAINER_NAME" ]; then
    echo "❌ No PostgreSQL container found!"
    exit 1
fi

echo "✅ Found container: $CONTAINER_NAME"
echo ""

# Get container port mapping
echo "Container port mapping:"
docker port $CONTAINER_NAME
echo ""

# Test connection from host
echo "Testing connection from host machine..."
echo "Connection string: jdbc:postgresql://localhost:5432/payment_ledger"
echo ""

# Try to connect using psql from host (if available)
if command -v psql &> /dev/null; then
    echo "Attempting psql connection from host..."
    PGPASSWORD="" psql -h localhost -p 5432 -U postgres -d payment_ledger -c "SELECT current_user, current_database();" 2>&1
else
    echo "psql not installed on host. Testing via Docker exec..."
    docker exec $CONTAINER_NAME psql -U postgres -d payment_ledger -c "SELECT current_user, current_database();"
fi

echo ""
echo "If the above works, check your application.properties connection string matches:"
echo "  - Host: localhost"
echo "  - Port: 5432 (or the mapped port above)"
echo "  - Database: payment_ledger"
echo "  - Username: postgres"
echo "  - Password: (empty or your password)"

