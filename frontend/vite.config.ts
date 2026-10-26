import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // The backend serves these files from its jar (classpath folder "webroot").
    outDir: '../backend/src/main/resources/webroot',
    emptyOutDir: true,
    // One bundle (React + Mantine, ~150 KB gzipped) is fine for this internal tool.
    chunkSizeWarningLimit: 1000,
  },
  server: {
    // During development (npm run dev) send API calls to the backend.
    proxy: {
      '/api': 'http://localhost:8080',
      '/health': 'http://localhost:8080',
    },
  },
});
