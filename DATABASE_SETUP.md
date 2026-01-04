# Database Setup Guide

## Setup with Docker Desktop

PostgreSQL should be run separately using Docker Desktop or your preferred Docker setup.

### 1. Start PostgreSQL Container

Run PostgreSQL using Docker Desktop or command line:

```bash
docker run -d \
  --name payment-ledger-postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=payment_ledger \
  -p 5432:5432 \
  -v payment-ledger-postgres-data:/var/lib/postgresql/data \
  postgres:15-alpine
```

Or use Docker Desktop GUI to create a container with:
- **Image:** `postgres:15-alpine`
- **Environment variables:**
  - `POSTGRES_USER=postgres`
  - `POSTGRES_PASSWORD=postgres`
  - `POSTGRES_DB=payment_ledger`
- **Port mapping:** `5432:5432`
- **Volume:** Create a volume for data persistence

### 2. Verify PostgreSQL is Running

```bash
# Check container status
docker ps | grep postgres

# Check logs
docker logs payment-ledger-postgres

# Test connection
docker exec -it payment-ledger-postgres psql -U postgres -d payment_ledger
```

### 3. Run the Application

The application is configured in `application.properties` to connect to:
- **Host:** `localhost:5432`
- **Database:** `payment_ledger`
- **Username:** `postgres`
- **Password:** `postgres`

Simply run:
```bash
./gradlew bootRun
```

Flyway will automatically create the database schema on first startup.

### 4. Customize Database Connection

If your PostgreSQL setup uses different credentials, you can:

**Option A:** Update `application.properties`:
```properties
spring.datasource.username=your_username
spring.datasource.password=your_password
```

**Option B:** Use environment variables:
```bash
export DB_USERNAME=your_username
export DB_PASSWORD=your_password
./gradlew bootRun
```

## Alternative: Use Existing PostgreSQL

If you have PostgreSQL installed locally:

### 1. Create Database and User

```sql
-- Connect to PostgreSQL as superuser
psql -U your_superuser

-- Create database
CREATE DATABASE payment_ledger;

-- Create user (if needed)
CREATE USER postgres WITH PASSWORD 'postgres';
ALTER USER postgres CREATEDB;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE payment_ledger TO postgres;
```

### 2. Update Configuration

Update `application.properties` or set environment variables as shown above.

## Troubleshooting

### Error: "role 'postgres' does not exist"

**Solution 1:** Create the user in your PostgreSQL
```sql
CREATE USER postgres WITH PASSWORD 'postgres';
ALTER USER postgres CREATEDB;
```

**Solution 2:** Update configuration to use your existing PostgreSQL user
```properties
spring.datasource.username=your_existing_user
spring.datasource.password=your_existing_password
```

### Error: "database 'payment_ledger' does not exist"

Create the database:
```sql
CREATE DATABASE payment_ledger;
```

Or ensure your Docker container has `POSTGRES_DB=payment_ledger` environment variable.

### Error: Connection refused

1. Check if PostgreSQL is running:
   ```bash
   # Docker
   docker ps | grep postgres
   
   # Local PostgreSQL
   # macOS
   brew services list
   # Linux
   sudo systemctl status postgresql
   ```

2. Check if port 5432 is available:
   ```bash
   lsof -i :5432
   ```

3. Verify connection string in `application.properties`

## Database Schema

The database schema is managed by Flyway migrations in:
- `src/main/resources/db/migration/V1__create_ledger_schema.sql`

Flyway will automatically run migrations on application startup.

## Reset Database (Fresh Start)

```bash
# Stop and remove container
docker stop payment-ledger-postgres
docker rm payment-ledger-postgres

# Remove volume (optional - removes all data)
docker volume rm payment-ledger-postgres-data

# Start fresh
docker run -d \
  --name payment-ledger-postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=payment_ledger \
  -p 5432:5432 \
  -v payment-ledger-postgres-data:/var/lib/postgresql/data \
  postgres:15-alpine
```

The application will automatically run Flyway migrations on startup.
