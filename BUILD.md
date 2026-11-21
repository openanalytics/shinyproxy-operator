# Building ShinyProxy Operator

This guide provides multiple options for building the ShinyProxy Operator without needing to install build tools locally.

## Prerequisites

Choose one of the following options:
- **Option 1**: Docker (recommended for most users)
- **Option 2**: Devbox (for development environments)
- **Option 3**: Local build (requires JDK 21 and Maven)

---

## Option 1: Build with Docker (Recommended)

This is the easiest option if you have Docker installed.

```bash
docker build -f Dockerfile.build --target artifacts -t shinyproxy-operator-builder -o ./. .

# the jar will be at ./shinyproxy-operator-jar-with-dependencies.jar
```

## Option 2: Build with Devbox

[Devbox](https://www.jetify.com/devbox) provides an isolated development environment without Docker.

### Install Devbox

```bash
curl -fsSL https://get.jetify.com/devbox | bash
```

### Build the Project

```bash
# Enter the devbox shell (installs JDK 21 and Maven automatically)
devbox shell

# Install the needed dependencies
devbox run install-deps

# Build the project
devbox run build

# Or build without tests (faster)
devbox run build-fast

# Or to use previous build steps (faster still)
devbox run package-fast

# The JAR will be at ./target/shinyproxy-operator-jar-with-dependencies.jar
```

## Verifying the Build

After building, verify the JAR exists:

```bash
# if using docker
ls -lh shinyproxy-operator-jar-with-dependencies.jar

# if using devbox
ls -lh target/shinyproxy-operator-jar-with-dependencies.jar
```

You should see a file of approximately 50-100 MB.

## Building a Docker Image

This needs to be copied into the image at https://github.com/openanalytics/shinyproxy-docker/.

First clonse the repo

```bash
# clone the repo
git clone git@github.com:openanalytics/shinyproxy-docker.git
cd shinyproxy-docker
```

Copy the jar and build the image

```bash
# copy the jar into the build context
cp ../shinyproxy-operator/shinyproxy-operator-jar-with-dependencies.jar Operator/.

# build the docker image
docker build -t shinyproxy-operator-dev:2.3.1 --build-arg JAR_LOCATION=shinyproxy-operator-jar-with-dependencies.jar Operator
```

## Troubleshooting

### Docker build fails with network errors

If you experience network issues during the Maven build:

```bash
# Build with host network (Linux only)
docker build --network=host -f Dockerfile.build --target artifacts -t shinyproxy-operator-builder -o ./. .
```

### Permission issues with target directory

```bash
# On Linux/Mac, you might need to fix permissions
sudo chown -R $USER:$USER target/
```

### Out of memory during build

```bash
# Increase Docker memory limit or add Maven options
docker build -f Dockerfile.build --target artifacts --build-arg MAVEN_OPTS="-Xmx2048m" -t shinyproxy-operator-builder -o ./. .
```

---

## Development Workflow

For ongoing development:

1. **Use Devbox** for the best development experience with automatic dependency management
1. **Use Docker** for one-off builds without installing tools

---

## Testing Your Changes

After building with your environment variable changes:

1. Create a test docker-compose.yml with the operator
2. Set `SHINYPROXY_ENV_*` environment variables
3. Verify they are passed to the ShinyProxy container

Example:
```yaml
services:
  shinyproxy-operator:
    image: shinyproxy-operator-dev:v2.3.1
    environment:
      - SPO_DOCKER_GID=999
      - SHINYPROXY_ENV_TEST_VAR=test_value
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```
