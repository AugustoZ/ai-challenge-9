/* ============================================================
   Стек модальных слоёв.
   ESC в ваниле закрывает только верхний слой: самописец →
   топливомер → drawer (script.js L1216–1227). Overlay и Drawer
   регистрируются здесь при открытии; закрывается лишь тот,
   кто на вершине стека, — порядок подписок на document роли
   не играет.
   ============================================================ */

const stack: symbol[] = [];

export function pushOverlay(): symbol {
  const token = Symbol('overlay');
  stack.push(token);
  return token;
}

export function popOverlay(token: symbol): void {
  const i = stack.lastIndexOf(token);
  if (i >= 0) stack.splice(i, 1);
}

export function isTopOverlay(token: symbol): boolean {
  return stack[stack.length - 1] === token;
}
