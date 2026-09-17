package com.google.android.gms.tasks;
import java.util.concurrent.Executor;
public class Task<T> {
 public boolean complete; public T result; private Executor executor; private OnCompleteListener<T> listener;
 public boolean isComplete(){return complete;} public T getResult(){return result;}
 public Task<T> addOnCompleteListener(Executor e,OnCompleteListener<T> l){executor=e;listener=l;if(complete)e.execute(()->l.onComplete(this));return this;}
 public void finish(T value){result=value;complete=true;if(listener!=null)executor.execute(()->listener.onComplete(this));}
}
