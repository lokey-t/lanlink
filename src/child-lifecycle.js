// Wait for owned helpers to release their executable and inherited working directory.
export function stopChild(child, {graceful = true, graceMs = 1500, killMs = 1500} = {}) {
 if (!child || child.exitCode !== null || child.signalCode !== null) return Promise.resolve();
 return new Promise((resolve, reject) => {
  let grace, deadline;
  const finish = error => {
   clearTimeout(grace); clearTimeout(deadline);
   child.removeListener('close', closed); child.removeListener('error', failed);
   error ? reject(error) : resolve();
  };
  const closed = () => finish();
  const failed = error => finish(error);
  const kill = () => {
   try { child.kill(); } catch (error) { finish(error); return; }
   deadline = setTimeout(() => finish(new Error('辅助进程未能退出')), killMs);
  };
  child.once('close', closed); child.once('error', failed);
  if (graceful && child.stdin && !child.stdin.destroyed) {
   grace = setTimeout(kill, graceMs);
   child.stdin.end();
  } else kill();
 });
}
