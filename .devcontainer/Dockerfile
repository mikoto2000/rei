FROM mcr.microsoft.com/devcontainers/java:3-25-trixie
LABEL maintainer "mikoto2000 <mikoto2000@gmail.com>"

ENV DEVIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends locales \
    && sed -i '/^# *ja_JP.UTF-8 UTF-8/s/^# *//' /etc/locale.gen \
    && locale-gen \
    && update-locale LANG=ja_JP.UTF-8 LANGUAGE=ja_JP:ja LC_ALL=ja_JP.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

ENV LANG=ja_JP.UTF-8
ENV LANGUAGE=ja_JP:ja
ENV LC_ALL=ja_JP.UTF-8

