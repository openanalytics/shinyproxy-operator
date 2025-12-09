# Environment Variables for ShinyProxy Container (Docker Mode)

## Overview

When running the ShinyProxy Operator in Docker mode, you can pass environment variables from the operator's environment to the ShinyProxy container. This is useful for injecting sensitive configuration values (like database passwords, API keys, etc.) without storing them in plain text in configuration files.

## How It Works

Any environment variable set in the operator's environment that starts with the prefix `SHINYPROXY_ENV_` will be automatically passed to the ShinyProxy container with the prefix stripped.

**Example:**
- Operator environment: `SHINYPROXY_ENV_DATABASE_URL=jdbc:postgresql://db:5432/myapp`
- ShinyProxy container receives: `DATABASE_URL=jdbc:postgresql://db:5432/myapp`

## Operator Configuration Variables

The following environment variables control the ShinyProxy Operator's behavior in Docker mode:

### SPO_REDIS_CONTAINER_NAME

**Default:** `sp-redis`

**Description:** Specifies the name of the Redis container that will be created and used by ShinyProxy for session storage. This allows you to customize the container name if needed (e.g., to avoid naming conflicts in multi-operator setups).

**Example:**
```bash
SPO_REDIS_CONTAINER_NAME=my-custom-redis
```

**Note:** If you change this value, the operator will create a new Redis container with the custom name and a corresponding data directory. Changing this after initial deployment will result in a new Redis instance; the old one will need to be manually cleaned up.

**Important:** The value of this variable is automatically used by ShinyProxy and Crane services through their Spring Boot configuration, so they will correctly reference the custom Redis container name.

### SPO_CADDY_CONTAINER_NAME

**Default:** `sp-caddy`

**Description:** Specifies the name of the Caddy reverse proxy container that will be created to manage ingress routing for ShinyProxy instances. This allows you to customize the container name if needed (e.g., to avoid naming conflicts in multi-operator setups).

**Example:**
```bash
SPO_CADDY_CONTAINER_NAME=my-custom-caddy
```

**Note:** If you change this value, the operator will create a new Caddy container with the custom name and a corresponding data directory. Changing this after initial deployment will result in a new Caddy instance; the old one will need to be manually cleaned up.

## Usage with Docker Compose

This feature is designed to work seamlessly with Docker Compose and environment files:

### Example docker-compose.yml

```yaml
version: '3.8'

services:
  shinyproxy-operator:
    image: openanalytics/shinyproxy-operator:latest
    environment:
      # Operator configuration
      - SPO_DOCKER_GID=999
      - SPO_REDIS_CONTAINER_NAME=sp-redis
      - SPO_CADDY_CONTAINER_NAME=sp-caddy
      
      # Environment variables to pass to ShinyProxy container
      - SHINYPROXY_ENV_DATABASE_URL=jdbc:postgresql://db:5432/myapp
      - SHINYPROXY_ENV_DATABASE_USER=shinyproxy
      - SHINYPROXY_ENV_DATABASE_PASSWORD=secret123
      - SHINYPROXY_ENV_SPRING_PROFILES_ACTIVE=production
      - SHINYPROXY_ENV_LOG_LEVEL=INFO
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ./config:/opt/shinyproxy-operator/config
      - ./data:/opt/shinyproxy-operator/data
```

### Using Environment Files

For better security, store sensitive values in an environment file:

**shinyproxy.env:**
```bash
SHINYPROXY_ENV_DATABASE_PASSWORD=super_secret_password
SHINYPROXY_ENV_API_KEY=my_api_key_12345
SHINYPROXY_ENV_REDIS_PASSWORD=redis_secret
```

**docker-compose.yml:**
```yaml
version: '3.8'

services:
  shinyproxy-operator:
    image: openanalytics/shinyproxy-operator:latest
    env_file:
      - shinyproxy.env
    environment:
      - SPO_DOCKER_GID=999
      - SHINYPROXY_ENV_SPRING_PROFILES_ACTIVE=production
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ./config:/opt/shinyproxy-operator/config
      - ./data:/opt/shinyproxy-operator/data
```

**Important:** Make sure to add `shinyproxy.env` to your `.gitignore` file to avoid committing secrets to version control.

## Use Cases

### 1. Database Credentials

```yaml
environment:
  - SHINYPROXY_ENV_SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/myapp
  - SHINYPROXY_ENV_SPRING_DATASOURCE_USERNAME=dbuser
  - SHINYPROXY_ENV_SPRING_DATASOURCE_PASSWORD=dbpass
```

### 2. External Service API Keys

```yaml
environment:
  - SHINYPROXY_ENV_EXTERNAL_API_KEY=your_api_key_here
  - SHINYPROXY_ENV_EXTERNAL_API_ENDPOINT=https://api.example.com
```

### 3. Spring Boot Configuration

```yaml
environment:
  - SHINYPROXY_ENV_SPRING_PROFILES_ACTIVE=production
  - SHINYPROXY_ENV_LOGGING_LEVEL_EU_OPENANALYTICS=DEBUG
  - SHINYPROXY_ENV_SERVER_PORT=8080
```

### 4. Redis Configuration (when using Redis for session storage)

```yaml
environment:
  - SHINYPROXY_ENV_SPRING_REDIS_PASSWORD=${REDIS_PASSWORD}
  - SHINYPROXY_ENV_SPRING_REDIS_HOST=redis
  - SHINYPROXY_ENV_SPRING_REDIS_PORT=6379
```

## Important Notes

- **Prefix Required:** Only environment variables starting with `SHINYPROXY_ENV_` are passed through
- **Prefix Stripped:** The `SHINYPROXY_ENV_` prefix is automatically removed when setting the variable in the ShinyProxy container
- **Variable Name Validation:** The variable name after stripping the prefix must not be empty (i.e., don't use `SHINYPROXY_ENV_` as the exact variable name)
- **Value Validation:** Environment variable values cannot contain newline characters (`\n` or `\r`) for security reasons
- **Invalid Variables:** Environment variables that don't pass validation are logged with a warning and skipped
- **Logging:** Each passed environment variable is logged (variable name only, not the value) when the container is created
- **Docker Only:** This feature is only available for Docker backend deployments
- **Kubernetes:** For Kubernetes deployments, use `kubernetesPodTemplateSpecPatches` to inject environment variables or reference secrets

## Security Best Practices

1. **Use Environment Files:** Store secrets in `.env` files that are excluded from version control
2. **File Permissions:** Restrict permissions on environment files (e.g., `chmod 600 shinyproxy.env`)
3. **Docker Secrets:** Consider using Docker Swarm secrets for production deployments
4. **Rotate Credentials:** Regularly rotate sensitive credentials
5. **Minimal Access:** Only pass environment variables that are actually needed by ShinyProxy

## Troubleshooting

### Environment variables not appearing in ShinyProxy

1. Verify the prefix is correct: `SHINYPROXY_ENV_` (note the underscore at the end)
2. Check the operator logs for messages about passed environment variables
3. Ensure the environment variable is set in the operator's environment, not in the ShinyProxy config

### Conflicts with existing variables

If you set an environment variable that conflicts with system variables (like `PROXY_VERSION`, `PROXY_REALM_ID`, or `SPRING_CONFIG_IMPORT`), the system variables will take precedence. The operator will log a warning if this occurs.

## Example: Full Docker Compose Setup

```yaml
version: '3.8'

services:
  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD}
    volumes:
      - redis-data:/data

  database:
    image: postgres:15-alpine
    environment:
      - POSTGRES_DB=shinyproxy
      - POSTGRES_USER=shinyproxy
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    volumes:
      - db-data:/var/lib/postgresql/data

  shinyproxy-operator:
    image: openanalytics/shinyproxy-operator:latest
    environment:
      # Operator configuration
      - SPO_DOCKER_GID=999
      
      # ShinyProxy environment variables
      - SHINYPROXY_ENV_SPRING_REDIS_PASSWORD=${REDIS_PASSWORD}
      - SHINYPROXY_ENV_SPRING_DATASOURCE_URL=jdbc:postgresql://database:5432/shinyproxy
      - SHINYPROXY_ENV_SPRING_DATASOURCE_USERNAME=shinyproxy
      - SHINYPROXY_ENV_SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
      - SHINYPROXY_ENV_SPRING_PROFILES_ACTIVE=production
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ./config:/opt/shinyproxy-operator/config
      - ./data:/opt/shinyproxy-operator/data
    depends_on:
      - redis
      - database

volumes:
  redis-data:
  db-data:
```

**.env file:**
```bash
REDIS_PASSWORD=secure_redis_password
DB_PASSWORD=secure_database_password
```
