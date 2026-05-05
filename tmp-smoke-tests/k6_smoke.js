import http from 'k6/http';
import { sleep } from 'k6';
export const options = { vus: 2, duration: '10s' };
export default function () {
  http.get('http://test-app-1:8080/api/test/ping');
  sleep(1);
}
