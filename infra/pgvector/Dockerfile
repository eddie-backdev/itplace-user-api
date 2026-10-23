FROM postgres:18.4-trixie

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        postgresql-18-postgis-3 \
        postgresql-18-postgis-3-scripts \
        postgresql-18-pgvector \
    && rm -rf /var/lib/apt/lists/*
