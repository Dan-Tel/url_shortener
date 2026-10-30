import http from 'k6/http';
import { config } from '../config.js';

export function resolveUrl(shortCode) {
    return http.get(
        `${config.baseUrl}/api/v1/urls/${shortCode}`,
        {
            timeout: '2s',
            tags: {
                protocol: 'rest',
                operation: 'read',
            },
        }
    );
}