# Asynchronous request queue

An implementation of an asynchronous request queue, based on the
problem [ThePrimeagen](https://www.youtube.com/ThePrimeagen) discussed in his
video: [Required 5 Math Skills for Programming](https://www.youtube.com/watch?v=3v_oXH3y5uM&ab_channel=ThePrimeTime).

Transcript:
We have a queue which returns a promise, and you have to hand it a promise factory. Call the promise factory and your
job gets executed. The async request queue can only have up to 3 running tasks at any one time.

Underlying structure can be anything, like an array or linked list.