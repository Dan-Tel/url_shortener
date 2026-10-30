export const config = {
    protocol: __ENV.PROTOCOL || 'rest',
    readMode: __ENV.READ_MODE || 'hot',

    baseUrl: __ENV.BASE_URL || 'http://127.0.0.1:8080',
    grpcAddress: __ENV.GRPC_ADDRESS || '127.0.0.1:9090',

    shortCode: __ENV.SHORT_CODE,

    rps: Number(__ENV.RPS || 100),
    duration: __ENV.DURATION || '30s',
};