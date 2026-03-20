// AUTO-GENERATED FILE — DO NOT HAND-EDIT
// Source: bridge-contract.yaml
// Regenerate: npm run generate
// Any manual changes will be overwritten on next generate.

// --- Native Bridge Globals ---
export declare interface Loop$gallery {
    getHistory(): string;
    launchActivity(manifestUrl: string): void;
    prepareScanner(): void;
    openScanner(): void;
    closeScanner(): void;
}

// --- Window augmentation ---
declare global {
    interface Window {
        'Loop$gallery'?: Loop$gallery;
    }
}
