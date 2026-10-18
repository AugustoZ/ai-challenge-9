/* ============================================================
   Выбор модели LLM — чистая логика. Порт 1:1 из
   static/script.js L925–980: составное значение опции
   «providerId/modelId», сборка optgroup-групп, единственный
   вариант «по умолчанию», разбор значения для ChatRequest.
   ============================================================ */

import type { ProviderModels } from '../../api/types';

/** Значение селекта «providerId/modelId» → { provider, model } для ChatRequest (script.js L975–980). */
export function splitModelValue(value: string): { provider: string | null; model: string | null } {
  const sep = value.indexOf('/');
  if (sep <= 0) return { provider: null, model: value || null };
  return { provider: value.slice(0, sep), model: value.slice(sep + 1) || null };
}

/** Вариант селекта: подпись = id модели, значение = составное «providerId/modelId». */
export interface ModelOption {
  value: string;
  label: string;
}

/** Группа провайдера — optgroup ванили (script.js L951–957). */
export interface ModelGroup {
  label: string;
  options: ModelOption[];
}

/**
 * Опции одного провайдера (script.js L949–958): пустые группы и
 * не-строки моделей пропускаются, заголовок — name или id.
 */
export function providerToGroup(provider: ProviderModels | null | undefined): ModelGroup {
  if (!provider) return { label: '', options: [] };
  const models = Array.isArray(provider.models) ? provider.models : [];
  const options: ModelOption[] = [];
  for (const id of models) {
    if (typeof id !== 'string' || !id) continue;
    options.push({ label: id, value: `${provider.id}/${id}` });
  }
  return { label: provider.name || provider.id, options };
}

/** Собранный селект: группы + единственный вариант по умолчанию. */
export interface BuiltModelSelect {
  /** optgroup-группы провайдеров (только с непустыми списками моделей). */
  groups: ModelGroup[];
  /**
   * Единственный вариант «по умолчанию», когда ни одна группа
   * не загрузилась (script.js L960–963); null — показывать нечего.
   */
  extraOption: ModelOption | null;
  /** Составное значение дефолта «defaultProvider/defaultModel» или ''. */
  fallbackValue: string;
}

/** Сборка селекта из ответа /api/models (script.js L946–963). */
export function buildModelSelect(
  providers: ProviderModels[] | null | undefined,
  defaultProvider: string,
  defaultModel: string,
): BuiltModelSelect {
  const groups: ModelGroup[] = [];
  for (const provider of Array.isArray(providers) ? providers : []) {
    const built = providerToGroup(provider);
    if (built.options.length) groups.push(built);
  }
  const fallbackValue = defaultProvider && defaultModel ? `${defaultProvider}/${defaultModel}` : '';
  const extraOption =
    !groups.length && fallbackValue ? { label: defaultModel, value: fallbackValue } : null;
  return { groups, extraOption, fallbackValue };
}

/** Все допустимые значения опций собранного селекта (script.js L965). */
export function modelSelectValues(built: BuiltModelSelect): string[] {
  return [
    ...built.groups.flatMap((group) => group.options.map((option) => option.value)),
    ...(built.extraOption ? [built.extraOption.value] : []),
  ];
}

/**
 * Выбор при загрузке списка: сохранённая модель, если она ещё есть
 * в списке, иначе дефолт (script.js L966–967); '' — нечего выбрать.
 */
export function resolveModelValue(
  values: readonly string[],
  savedModel: string,
  fallbackValue: string,
): string {
  const chosen = savedModel && values.includes(savedModel) ? savedModel : fallbackValue;
  return chosen && values.includes(chosen) ? chosen : '';
}
