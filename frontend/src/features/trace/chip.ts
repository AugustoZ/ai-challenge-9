/* Чип «скачать <файл>» для tool_call инструмента write_file — чистая
   логика. Порт 1:1 из static/script.js L447–459 (buildDownloadChip):
   путь берётся из JSON-аргументов; если аргументы не распарсились или
   path отсутствует — чип молча не строится. */
export function downloadChipPath(argumentsJson: string): string | null {
  try {
    const args: unknown = JSON.parse(argumentsJson);
    const path = (args as { path?: unknown } | null)?.path;
    if (typeof path !== 'string' || !path) return null;
    return path;
  } catch {
    return null;
  }
}
