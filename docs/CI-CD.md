# CI/CD Documentation

## Build and Push Docker Image Workflow

This repository includes a GitHub Actions workflow that automatically builds the shinyproxy-operator JAR and creates a Docker image that is pushed to a Container Registry.

### Workflow File

The workflow is defined in `.github/workflows/build-and-push-docker.yaml`.

### Triggers

The workflow is triggered on:
- Pushes to `main` or `dev` branches
- Pull requests to `main` or `dev` branches
- Tag pushes matching `v*` pattern (e.g., `v1.0.0`)
- Manual workflow dispatch

### Workflow Steps

1. **Checkout shinyproxy-operator**: Clones this repository
2. **Set up JDK 21**: Installs Java 21 (Temurin distribution)
3. **Cache Maven packages**: Caches Maven dependencies for faster builds
4. **Build shinyproxy-operator JAR**: Runs `mvn -U clean install -DskipTests` to build the JAR
5. **Checkout shinyproxy-docker**: Clones the [openanalytics/shinyproxy-docker](https://github.com/openanalytics/shinyproxy-docker) repository
6. **Copy JAR to Docker build context**: Copies the built JAR to the Operator directory in shinyproxy-docker
7. **Set up Docker Buildx**: Configures Docker Buildx for advanced build features
8. **Login to Container Registry**: Authenticates with the Container Registry
9. **Extract metadata for Docker**: Generates Docker tags and labels based on Git context
10. **Build and push Docker image**: Builds the Docker image and pushes it to the registry

### Required Secrets

The workflow requires the following secrets to be configured in the GitHub repository:

#### `CR_REGISTRY`
- **Description**: Container Registry URL
- **Type**: Repository Secret
- **Usage**: The registry URL (e.g., `ghcr.io`, etc.)
- **Example**: `ghcr.io`

#### `CR_USERNAME`
- **Description**: Username for Container Registry authentication
- **Type**: Repository Secret
- **Usage**: Used to authenticate with the Container Registry

#### `CR_PASSWORD`
- **Description**: Password for Container Registry authentication
- **Type**: Repository Secret
- **Usage**: Used to authenticate with the Container Registry

### Setting Up Secrets

To configure these secrets:

1. Go to the GitHub repository
2. Navigate to **Settings** > **Secrets and variables** > **Actions**
3. Click **New repository secret**
4. Add each secret with the name and value:
   - Name: `CR_REGISTRY`, Value: Your Container Registry URL (e.g., `ghcr.io`)
   - Name: `CR_USERNAME`, Value: Your Container Registry username
   - Name: `CR_PASSWORD`, Value: Your Container Registry password

### Docker Image Tags

The workflow automatically generates tags based on the Git context:

- **Branch pushes**: `<branch-name>` (e.g., `main`, `dev`)
- **Pull requests**: `pr-<number>` (e.g., `pr-123`)
- **Version tags**: Multiple formats for semantic versioning
  - `<version>` (e.g., `1.2.3`)
  - `<major>.<minor>` (e.g., `1.2`)
  - `<major>` (e.g., `1`)
- **Dev branch version tags**: When triggered by the `dev` branch with a version tag
  - `dev-<version>` (e.g., `dev-1.2.3`)
  - `dev-<major>.<minor>` (e.g., `dev-1.2`)
  - `dev-<major>` (e.g., `dev-1`)
- **SHA-based**: `<sha>` (e.g., `abc1234`)

### Docker Registry

Images are pushed to the Container Registry specified by the `CR_REGISTRY` secret, under the repository name `shinyproxy-operator`.

### Build Arguments

The Docker build uses the following build argument:
- `JAR_LOCATION=shinyproxy-operator-jar-with-dependencies.jar`: Specifies the local JAR file to use instead of downloading from Nexus

### Caching

The workflow uses GitHub Actions cache for:
- Maven dependencies (`.m2` directory)
- Docker layer caching (GitHub Actions cache backend)

This improves build times for subsequent runs.

### Manual Workflow Dispatch

You can manually trigger the workflow:

1. Go to the **Actions** tab in the GitHub repository
2. Select the "Build and Push Docker Image" workflow
3. Click **Run workflow**
4. Select the branch to run from
5. Click **Run workflow**

### Troubleshooting

#### Build Failures

If the Maven build fails:
- Check that the POM file is valid
- Verify that all dependencies are accessible
- Review the build logs for specific error messages

#### Docker Push Failures

If pushing to the registry fails:
- Verify that `CR_REGISTRY`, `CR_USERNAME`, and `CR_PASSWORD` secrets are correctly set
- Ensure the credentials have push permissions to the registry
- Check that the registry URL in `CR_REGISTRY` is correct and accessible

#### JAR Not Found

If the JAR file is not found during the copy step:
- Verify the Maven build completed successfully
- Check that the JAR name matches: `shinyproxy-operator-jar-with-dependencies.jar`
- Review the build output for the correct target directory

### Local Development

To replicate the workflow locally:

```bash
# Step 1: Build the operator
git clone https://github.com/jaredlander/shinyproxy-operator.git
cd shinyproxy-operator
mvn -U clean install -DskipTests

# Step 2: Clone shinyproxy-docker
cd ..
git clone https://github.com/openanalytics/shinyproxy-docker.git
cd shinyproxy-docker/Operator

# Step 3: Copy the JAR
cp ../../shinyproxy-operator/target/shinyproxy-operator-jar-with-dependencies.jar .

# Step 4: Build the Docker image
docker build -t shinyproxy-operator-dev --build-arg JAR_LOCATION=shinyproxy-operator-jar-with-dependencies.jar .

# Step 5: Tag and push (optional)
docker tag shinyproxy-operator-dev <your-registry>/shinyproxy-operator:dev
docker push <your-registry>/shinyproxy-operator:dev
```
