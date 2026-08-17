const trimTrailingSlash = (value: string) => value.replace(/\/+$/, '');

export const env = {
  apiBaseUrl: trimTrailingSlash(import.meta.env.VITE_API_BASE_URL ?? ''),
  drawioBaseUrl:
    import.meta.env.VITE_DRAWIO_BASE_URL || 'https://embed.diagrams.net',
} as const;
