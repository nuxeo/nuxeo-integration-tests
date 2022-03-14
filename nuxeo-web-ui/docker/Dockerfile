# vim:set ft=dockerfile:
ARG BASE_IMAGE

FROM ${BASE_IMAGE}

ARG VERSION
ARG SCM_REF
ARG BUILD_TAG
ARG CLID
ARG WEB_UI_CONNECT_URL
ARG NUXEO_CONNECT_URL
ARG NUXEO_WEB_UI_VERSION

LABEL com.customer.sample.version=${VERSION}
LABEL com.customer.sample.scm-ref=${SCM_REF}
LABEL com.customer.sample.build-tag=${BUILD_TAG}

COPY nuxeo-web-ui-marketplace-itests-*.zip /nuxeo-web-ui-marketplace-itests.zip

# Install required nuxeo packages
RUN /install-packages.sh --offline /nuxeo-web-ui-marketplace-itests.zip
RUN /install-packages.sh --clid ${CLID} --connect-url ${WEB_UI_CONNECT_URL} nuxeo-web-ui-${NUXEO_WEB_UI_VERSION}
RUN /install-packages.sh --clid ${CLID} --connect-url ${NUXEO_CONNECT_URL} nuxeo-csv

# Install ffmpeg
USER 0
RUN yum localinstall -y --nogpgcheck https://download1.rpmfusion.org/free/el/rpmfusion-free-release-7.noarch.rpm  \
    && yum install -y ffmpeg
USER 900
