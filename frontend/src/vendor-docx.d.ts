declare module 'docx-preview' {
  export function renderAsync(
    data: Blob | ArrayBuffer | Uint8Array,
    bodyContainer: HTMLElement,
    styleContainer?: HTMLElement | null,
    options?: Record<string, unknown>,
  ): Promise<void>;
}

declare module '@sharp9/ofdjs' {
  export function readOfd(input: Uint8Array | ArrayBuffer | File | Blob): Promise<unknown>;
  export function getPageCount(ofd: unknown): number;
  export function getPageDimensions(ofd: unknown, pageIndex: number): Promise<{ width: number; height: number }>;
  export function renderPageToCanvas(
    ofd: unknown,
    pageIndex: number,
    canvas: HTMLCanvasElement,
    options?: { dpi?: number },
  ): Promise<void>;
}
