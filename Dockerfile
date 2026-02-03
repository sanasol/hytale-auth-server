FROM node:20-slim

WORKDIR /app

# Install unzip for Assets.zip extraction
# Install build tools and libraries for native SSR dependencies (gl, canvas, sharp)
RUN apt-get update && apt-get install -y --no-install-recommends \
    unzip \
    # Build tools
    python3 \
    build-essential \
    # For sharp
    libvips-dev \
    # For canvas
    libcairo2-dev \
    libpango1.0-dev \
    libjpeg-dev \
    libgif-dev \
    librsvg2-dev \
    # For gl (headless-gl) - Mesa with OSMesa (dev + runtime)
    libgl1-mesa-dev \
    libgl1-mesa-dri \
    libosmesa6-dev \
    libosmesa6 \
    libxi-dev \
    libxext-dev \
    xvfb \
    xauth \
    && rm -rf /var/lib/apt/lists/* \
    && ln -sf /usr/bin/python3 /usr/bin/python

# Environment variables for headless GL software rendering (OSMesa)
ENV LIBGL_ALWAYS_SOFTWARE=1
ENV LIBGL_ALWAYS_INDIRECT=0
ENV MESA_GL_VERSION_OVERRIDE=3.3

# Create directories
RUN mkdir -p /app/data /app/assets

# Copy package files and install dependencies
COPY package*.json ./
RUN npm install --omit=dev && npm cache clean --force

# Copy source code
COPY src/ ./src/

# Copy static assets
COPY assets/avatar.js ./assets/
COPY assets/customizer.html ./assets/
COPY assets/thumbnail-renderer.js ./assets/

EXPOSE 3000

# Create startup script that runs Xvfb in background then starts node
RUN echo '#!/bin/bash\nXvfb :99 -screen 0 1024x768x24 &\nsleep 1\nexec node src/app.js' > /app/start.sh && chmod +x /app/start.sh

ENV DISPLAY=:99

CMD ["/app/start.sh"]
