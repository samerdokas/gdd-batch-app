package lt.samerdokas.gdd.batch;

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class TaskManager {
    private final AtomicInteger threadId = new AtomicInteger();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(Env.getWorkerCount(), (r) -> new Thread(r, "GDD-Worker-" + threadId.incrementAndGet()));
    private final Deque<Future<?>> tasks = new LinkedList<>();
    
    public void submit(Runnable task) {
        synchronized (tasks) {
            tasks.add(executor.submit(task));
        }
    }
    
    public void schedule(Runnable task, int delaySeconds) {
        synchronized (tasks) {
            tasks.add(executor.schedule(task, delaySeconds, TimeUnit.SECONDS));
        }
    }
    
    public void awaitAllTasks() throws ExecutionException, InterruptedException {
        Future<?> task;
        while (true) {
            synchronized (tasks) {
                task = tasks.poll();
            }
            if (task == null) {
                return;
            }
            task.get();
        }
    }
    
    public void shutdown() throws InterruptedException {
        executor.shutdown();
        while (true) {
            if (executor.awaitTermination(1, TimeUnit.DAYS)) {
                break;
            }
        }
    }
}
