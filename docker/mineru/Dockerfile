# MinerU 官方国内 Dockerfile，FROM 使用本机已拉取的底包。
FROM docker.m.daocloud.io/vllm/vllm-openai:v0.21.0

RUN apt-get update && \
    apt-get install -y \
        fonts-noto-core \
        fonts-noto-cjk \
        fontconfig \
        libgl1 && \
    fc-cache -fv && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN python3 -m pip install -U 'mineru[core]>=3.4.0' -i https://mirrors.aliyun.com/pypi/simple --break-system-packages && \
    python3 -m pip cache purge

RUN /bin/bash -c "mineru-models-download -s modelscope -m all"

ENTRYPOINT ["/bin/bash", "-c", "export MINERU_MODEL_SOURCE=local && exec \"$@\"", "--"]
