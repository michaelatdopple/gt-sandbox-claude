// Hand-written DX types for internal SDK (gallery webview only)
// Bridge contract: see bridge-contract.yaml (scope: internal)

// ==================== Gallery Types ====================

interface GalleryHistoryEntry {
    projectId: string;
    activityName: string;
    manifestUrl: string;
    thumbnailUrl: string | null;
    launchCount: number;
}

interface GalleryAPI {
    getHistory(): GalleryHistoryEntry[];
    launchActivity(manifestUrl: string): void;
    prepareScanner(): void;
    openScanner(): void;
    closeScanner(): void;
}

// ==================== Main SDK Interface ====================

interface LoopInternalSDK {
    readonly gallery: GalleryAPI;
}

// ==================== Global Declarations ====================

declare global {
    interface Window {
        LoopInternal: LoopInternalSDK;
    }
    const LoopInternal: LoopInternalSDK;
}

export {};
