// AUTO-GENERATED FILE — DO NOT HAND-EDIT
// Source: bridge-contract.yaml
// Regenerate: npm run generate
// Any manual changes will be overwritten on next generate.

// --- Native Bridge Globals ---
export declare interface Loop$motion {
    subscribe(): string;
    unsubscribe(id: string): boolean;
    setFrequency(hz: number): number;
    setSmoothingAlpha(alpha: number): number;
    getStatus(): string;
    getLatest(): string | null;
    getSensorAvailability(): string;
}

export declare interface Loop$buttons {}

export declare interface Loop$haptics {
    getStatus(): string;
    pulse(intensity: number): string;
    playCurve(curveJson: string): string;
    stop(): string;
}

export declare interface Loop$match {
    getStatus(): string;
    isBusy(): boolean;
    captureFrame(requestId: number, base64Image: string): void;
}

export declare interface Loop$pack {
    getStatus(): string;
    getAsset(packName: string, path: string): string;
    assetExists(packName: string, path: string): boolean;
}

export declare interface Loop$manifest {
    load(url: string): void;
}

export declare interface Loop$ble {
    createGame(gameId: string): string;
    createGame(gameId: string, token?: string): string;
    joinGame(hostToken: string, playerName: string): string;
    playGame(seed: string, playerName: string): string;
    getState(): string;
    endGame(): void;
    leaveGame(): void;
    send(dataJson: string, optionsJson: string): string;
}

export declare interface Loop$storage {
    setItem(key: string, value: string): string;
    getItem(key: string): string | null;
    removeItem(key: string): string;
    clear(): string;
    keys(): string;
    getUsage(): string;
    exists(key: string): boolean;
}

export declare interface Loop$system {
    isFreeRotateEnabled(): string;
    setFreeRotate(enabled: boolean): string;
}

// --- Window augmentation ---
declare global {
    interface Window {
        'Loop$motion'?: Loop$motion;
        'Loop$buttons'?: Loop$buttons;
        'Loop$haptics'?: Loop$haptics;
        'Loop$match'?: Loop$match;
        'Loop$pack'?: Loop$pack;
        'Loop$manifest'?: Loop$manifest;
        'Loop$ble'?: Loop$ble;
        'Loop$storage'?: Loop$storage;
        'Loop$system'?: Loop$system;
    }
}
