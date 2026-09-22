/**
 * 防抖：wait 毫秒内的重复调用只生效一次。
 *
 * immediate = false（尾执行）：停止触发 wait 毫秒后执行 —— 适合搜索框，减少无效请求
 * immediate = true （首执行）：首次立即执行，wait 内的重复调用被丢弃 —— 适合按钮防连点，
 *                              用户点下去马上有反馈，不用等 300ms
 *
 * 返回的函数带 cancel()，组件卸载或重置筛选条件时可取消挂起的调用，
 * 避免在组件已销毁后仍去修改状态。
 */
export function debounce(fn, wait = 300, immediate = false) {
  let timer = null

  const debounced = function (...args) {
    const callNow = immediate && timer === null

    if (timer) {
      clearTimeout(timer)
    }
    timer = setTimeout(() => {
      timer = null
      if (!immediate) {
        fn.apply(this, args)
      }
    }, wait)

    if (callNow) {
      fn.apply(this, args)
    }
  }

  debounced.cancel = () => {
    if (timer) {
      clearTimeout(timer)
    }
    timer = null
  }

  return debounced
}
