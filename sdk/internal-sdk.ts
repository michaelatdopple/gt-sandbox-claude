// Internal SDK — privileged APIs for gallery webview only
// Bridge contract: see bridge-contract.yaml (scope: internal)
import type { Loop$gallery } from './generated/internal-types';

// ==================== Native Bridge Accessors ====================

const native = {
    get gallery(): Loop$gallery | undefined { return window.Loop$gallery; },
};

// ==================== Internal Types ====================

interface GalleryHistoryEntry {
    projectId: string;
    activityName: string;
    manifestUrl: string;
    thumbnailUrl: string | null;
    launchCount: number;
}

// ==================== Gallery API ====================

const GalleryAPI = {
    getHistory(): GalleryHistoryEntry[] {
        if (!native.gallery) return [];
        try {
            return JSON.parse(native.gallery.getHistory()) as GalleryHistoryEntry[];
        } catch (e) {
            return [];
        }
    },

    launchActivity(manifestUrl: string): void {
        if (native.gallery) {
            native.gallery.launchActivity(manifestUrl);
        }
    },

    prepareScanner(): void {
        if (native.gallery) {
            native.gallery.prepareScanner();
        }
    },

    openScanner(): void {
        if (native.gallery) {
            native.gallery.openScanner();
        }
    },

    closeScanner(): void {
        if (native.gallery) {
            native.gallery.closeScanner();
        }
    }
};

// ==================== Main LoopInternal Object ====================

const LoopInternal = {
    gallery: GalleryAPI
};

// Expose globally
(window as any).LoopInternal = LoopInternal;

// Dispatch ready event
window.dispatchEvent(new CustomEvent('loopinternal:ready'));
