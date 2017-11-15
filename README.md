# akka-pubsub architecture

Akka PubSub is a highly scalable, distributed publish-subscribe mechanism. It is composed of three layers;

There is a randomized layer in the form of a peer sampling service, a clustering layer in the form of vicinity and a dissemination layer for distributing messages to
any subscribed parties.

```
DISSEM_1 <-> DISSEM_2
   ^           ^
STRUCT_1 <-> STRUCT_2
   ^           ^
RANDOM_1 <-> RANDOM_2
```

