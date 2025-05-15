# Use an official Alpine image
FROM alpine:latest

# Set a working directory inside the container
WORKDIR /app

# Run a basic command to test (echo Hello World)
CMD ["echo", "Hello World from Docker!"]
