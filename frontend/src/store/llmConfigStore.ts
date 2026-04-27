import { create } from 'zustand';
import { LLMGlobalConfig, LLMConfigRequest } from '../api/llmConfig';
import { deleteConfig as deleteLLMConfig, getAllConfigs, getConfigsByProvider, saveConfig as saveLLMConfig, setDefaultConfig as setLLMDefaultConfig } from '../api/llmConfig';

interface LLMConfigState {
  configs: LLMGlobalConfig[];
  loading: boolean;
  error: string | null;
  fetchAllConfigs: () => Promise<void>;
  fetchConfigsByProvider: (provider: string) => Promise<void>;
  saveConfig: (config: LLMConfigRequest) => Promise<LLMGlobalConfig | null>;
  deleteConfig: (id: number) => Promise<boolean>;
  setDefaultConfig: (id: number) => Promise<boolean>;
  getConfigsByProvider: (provider: string) => LLMGlobalConfig[];
  getDefaultConfig: (provider: string) => LLMGlobalConfig | undefined;
  clearError: () => void;
}

const getErrorMessage = (error: unknown, fallback: string): string => {
  return error instanceof Error ? error.message : fallback;
};

export const useLLMConfigStore = create<LLMConfigState>((set, get) => ({
  configs: [],
  loading: false,
  error: null,

  fetchAllConfigs: async () => {
    set({ loading: true, error: null });
    try {
      const result = await getAllConfigs();
      if (result.code === 200) {
        set({ configs: result.data, loading: false });
      } else {
        set({ error: result.message, loading: false });
      }
    } catch (error: unknown) {
      set({ error: getErrorMessage(error, '获取配置列表失败'), loading: false });
    }
  },

  fetchConfigsByProvider: async (provider: string) => {
    set({ loading: true, error: null });
    try {
      const result = await getConfigsByProvider(provider);
      if (result.code === 200) {
        set((state) => {
          const otherConfigs = state.configs.filter((config) => config.provider !== provider);
          return {
            configs: [...otherConfigs, ...result.data],
            loading: false
          };
        });
      } else {
        set({ error: result.message, loading: false });
      }
    } catch (error: unknown) {
      set({ error: getErrorMessage(error, '获取配置列表失败'), loading: false });
    }
  },

  saveConfig: async (config: LLMConfigRequest) => {
    set({ loading: true, error: null });
    try {
      const result = await saveLLMConfig(config);
      if (result.code === 200) {
        await get().fetchAllConfigs();
        return result.data;
      }

      set({ error: result.message, loading: false });
      return null;
    } catch (error: unknown) {
      set({ error: getErrorMessage(error, '保存配置失败'), loading: false });
      return null;
    }
  },

  deleteConfig: async (id: number) => {
    set({ loading: true, error: null });
    try {
      const result = await deleteLLMConfig(id);
      if (result.code === 200) {
        set((state) => ({
          configs: state.configs.filter((config) => config.id !== id),
          loading: false
        }));
        return true;
      }

      set({ error: result.message, loading: false });
      return false;
    } catch (error: unknown) {
      set({ error: getErrorMessage(error, '删除配置失败'), loading: false });
      return false;
    }
  },

  setDefaultConfig: async (id: number) => {
    set({ loading: true, error: null });
    try {
      const result = await setLLMDefaultConfig(id);
      if (result.code === 200) {
        await get().fetchAllConfigs();
        return true;
      }

      set({ error: result.message, loading: false });
      return false;
    } catch (error: unknown) {
      set({ error: getErrorMessage(error, '设置默认配置失败'), loading: false });
      return false;
    }
  },

  getConfigsByProvider: (provider: string) => {
    return get().configs.filter((config) => config.provider === provider);
  },

  getDefaultConfig: (provider: string) => {
    return get().configs.find((config) => config.provider === provider && config.isDefault === 1);
  },

  clearError: () => set({ error: null })
}));
