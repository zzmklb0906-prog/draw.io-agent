import { create } from 'zustand';

export interface ModelSettings {
  enabled: boolean;
  customBaseUrl: string;
  customApiKey: string;
  customCompletionsPath: string;
  customModel: string;
}

interface ModelSettingsState extends ModelSettings {
  setSettings: (settings: ModelSettings) => void;
  clear: () => void;
}

const empty: ModelSettings = {
  enabled: false,
  customBaseUrl: '',
  customApiKey: '',
  customCompletionsPath: '',
  customModel: '',
};

export const useModelSettingsStore = create<ModelSettingsState>((set) => ({
  ...empty,
  setSettings: (settings) => set(settings),
  clear: () => set(empty),
}));
