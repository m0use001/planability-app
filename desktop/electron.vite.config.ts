import { defineConfig, externalizeDepsPlugin } from 'electron-vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
	main: {
		// MCP SDK is ESM-only; bundle it so the CJS main can require it.
		plugins: [externalizeDepsPlugin({ exclude: ['@modelcontextprotocol/sdk'] })],
		resolve: {
			alias: { '@shared': path.resolve(__dirname, 'shared') }
		}
	},
	preload: {
		plugins: [externalizeDepsPlugin()],
		resolve: {
			alias: { '@shared': path.resolve(__dirname, 'shared') }
		}
	},
	renderer: {
		resolve: {
			alias: {
				'@renderer': path.resolve(__dirname, 'src/renderer/src'),
				'@shared': path.resolve(__dirname, 'shared')
			}
		},
		plugins: [react()],
		build: {
			rollupOptions: {
				input: {
					// Main window.
					index: path.resolve(__dirname, 'src/renderer/index.html'),
					// Floating input/menu/output group (M21/F3). The main process loads
					// out/renderer/float.html; a separate entry keeps the float bundle small and
					// lets it boot without the full app's data layer.
					float: path.resolve(__dirname, 'src/renderer/float.html')
				}
			}
		}
	}
});
