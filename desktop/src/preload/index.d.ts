import type { PlanabilityApi } from './api';

declare global {
	interface Window {
		planability: PlanabilityApi;
	}
}

export {};
