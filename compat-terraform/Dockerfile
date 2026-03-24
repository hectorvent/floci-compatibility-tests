FROM hashicorp/terraform:1.14.7 AS terraform

FROM amazon/aws-cli:2.15.52

# Copy terraform binary from official Terraform image
COPY --from=terraform /bin/terraform /usr/local/bin/terraform

WORKDIR /app
COPY . .
RUN chmod +x run.sh

ENV FLOCI_ENDPOINT=http://floci:4566
ENV AWS_ENDPOINT_URL=http://floci:4566
ENV AWS_ACCESS_KEY_ID=test
ENV AWS_SECRET_ACCESS_KEY=test
ENV AWS_DEFAULT_REGION=us-east-1

ENTRYPOINT ["./run.sh"]
