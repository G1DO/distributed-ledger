---------------------------- MODULE SequentialKV ----------------------------
EXTENDS FiniteSets

CONSTANTS Keys, Values, Absent
ASSUME /\ IsFiniteSet(Keys) /\ Keys # {}
       /\ IsFiniteSet(Values) /\ Values # {}
       /\ Absent \notin Values

(* Keys and Values abstract valid Unicode strings; Values includes a class for
   the empty string. Absent represents JSON null, not an empty stored string.
   Invalid represents every rejected wire request, including parse failures.
   Its unused fields only normalize the finite abstract request record shape. *)
MaybeValue == Values \cup {Absent}
Invalid == [op |-> "INVALID", key |-> CHOOSE k \in Keys : TRUE,
            value |-> Absent, expected |-> Absent]
Requests == [op : {"GET", "DELETE"}, key : Keys,
             value : {Absent}, expected : {Absent}]
            \cup [op : {"PUT"}, key : Keys,
                  value : Values, expected : {Absent}]
            \cup [op : {"CAS"}, key : Keys,
                  value : Values, expected : MaybeValue]
            \cup {Invalid}
NoResponse == [status |-> "pending"]
Responses == {[status |-> "invalid"]}
             \cup [status : {"ok"}, value : MaybeValue]
             \cup [status : {"ok"}, previous : MaybeValue]
             \cup [status : {"ok"}, previous : MaybeValue, swapped : BOOLEAN]

VARIABLES store, phase, request, before, response
vars == <<store, phase, request, before, response>>

Init == /\ store = [k \in Keys |-> Absent]
        /\ phase = "ready"
        /\ request = Invalid
        /\ before = store
        /\ response = NoResponse

(* One outstanding invocation. Retaining only its pre-state and latest result
   makes this a finite recurrent model, without bounding the number of calls. *)
Invoke == /\ phase \in {"ready", "complete"}
          /\ request' \in Requests
          /\ before' = store
          /\ phase' = "running"
          /\ response' = NoResponse
          /\ UNCHANGED store

(* The atomic completion is this sequential reference's linearization point.
   There is no crash, transport, persistence, retry identity, or replication. *)
Complete == /\ phase = "running"
            /\ phase' = "complete"
            /\ UNCHANGED <<request, before>>
            /\ CASE request.op = "GET" ->
                       /\ store' = store
                       /\ response' = [status |-> "ok", value |-> store[request.key]]
                 [] request.op = "PUT" ->
                       /\ store' = [store EXCEPT ![request.key] = request.value]
                       /\ response' = [status |-> "ok", previous |-> store[request.key]]
                 [] request.op = "DELETE" ->
                       /\ store' = [store EXCEPT ![request.key] = Absent]
                       /\ response' = [status |-> "ok", previous |-> store[request.key]]
                 [] request.op = "CAS" ->
                       /\ store' = IF store[request.key] = request.expected
                                    THEN [store EXCEPT ![request.key] = request.value]
                                    ELSE store
                       /\ response' = [status |-> "ok", previous |-> store[request.key],
                                        swapped |-> (store[request.key] = request.expected)]
                 [] request.op = "INVALID" ->
                       /\ store' = store
                       /\ response' = [status |-> "invalid"]

Next == Invoke \/ Complete
Spec == Init /\ [][Next]_vars
(* Weak fairness only applies once a request is invoked and remains enabled.
   No fairness of the caller, latency bound, or crash recovery is promised. *)
FairSpec == Spec /\ WF_vars(Complete)
RequestProgress == (phase = "running") ~> (phase = "complete")

TypeOK == /\ store \in [Keys -> MaybeValue]
          /\ before \in [Keys -> MaybeValue]
          /\ phase \in {"ready", "running", "complete"}
          /\ request \in Requests
          /\ response \in Responses \cup {NoResponse}

InvocationUnchanged == phase = "running" =>
                          /\ store = before
                          /\ response = NoResponse

(* Assertions use the saved invocation state, independently of Complete's
   current-state expressions. In particular, responses expose the old value. *)
ResponseCorrect == phase = "complete" =>
    CASE request.op = "GET" ->
             response = [status |-> "ok", value |-> before[request.key]]
      [] request.op \in {"PUT", "DELETE"} ->
             response = [status |-> "ok", previous |-> before[request.key]]
      [] request.op = "CAS" ->
             response = [status |-> "ok", previous |-> before[request.key],
                         swapped |-> (before[request.key] = request.expected)]
      [] request.op = "INVALID" -> response = [status |-> "invalid"]

OperationEffect == phase = "complete" =>
    CASE request.op \in {"GET", "INVALID"} -> store = before
      [] request.op = "PUT" -> store[request.key] = request.value
      [] request.op = "DELETE" -> store[request.key] = Absent
      [] request.op = "CAS" ->
             IF before[request.key] = request.expected
             THEN store[request.key] = request.value
             ELSE store = before

OperationFrame == phase = "complete" =>
    \A k \in Keys : k # request.key => store[k] = before[k]
=============================================================================
