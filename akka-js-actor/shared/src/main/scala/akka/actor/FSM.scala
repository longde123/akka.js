/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.implicitConversions
import scala.concurrent.duration.Duration
import scala.collection.mutable
import akka.routing.{ Deafen, Listen, Listeners }
import scala.concurrent.duration.FiniteDuration

object FSM {

  /**
   * A partial function value which does not match anything and can be used to
   * “reset” `whenUnhandled` and `onTermination` handlers.
   *
   * {{{
   * onTermination(FSM.NullFunction)
   * }}}
   */
  object NullFunction extends PartialFunction[Any, Nothing] {
    def isDefinedAt(o: Any) = false
    def apply(o: Any) = sys.error("undefined")
  }

  /**
   * Message type which is sent directly to the subscribed actor in
   * [[akka.actor.FSM.SubscribeTransitionCallBack]] before sending any
   * [[akka.actor.FSM.Transition]] messages.
   */
  case class CurrentState[S](fsmRef: ActorRef, state: S)

  /**
   * Message type which is used to communicate transitions between states to
   * all subscribed listeners (use [[akka.actor.FSM.SubscribeTransitionCallBack]]).
   */
  case class Transition[S](fsmRef: ActorRef, from: S, to: S)

  /**
   * Send this to an [[akka.actor.FSM]] to request first the [[FSM.CurrentState]]
   * and then a series of [[FSM.Transition]] updates. Cancel the subscription
   * using [[FSM.UnsubscribeTransitionCallBack]].
   */
  case class SubscribeTransitionCallBack(actorRef: ActorRef)

  /**
   * Unsubscribe from [[akka.actor.FSM.Transition]] notifications which was
   * effected by sending the corresponding [[akka.actor.FSM.SubscribeTransitionCallBack]].
   */
  case class UnsubscribeTransitionCallBack(actorRef: ActorRef)

  /**
   * Reason why this [[akka.actor.FSM]] is shutting down.
   */
  sealed trait Reason

  /**
   * Default reason if calling `stop()`.
   */
  case object Normal extends Reason

  /**
   * Reason given when someone was calling `system.stop(fsm)` from outside;
   * also applies to `Stop` supervision directive.
   */
  case object Shutdown extends Reason

  /**
   * Signifies that the [[akka.actor.FSM]] is shutting itself down because of
   * an error, e.g. if the state to transition into does not exist. You can use
   * this to communicate a more precise cause to the [[akka.actor.FSM.onTermination]] block.
   */
  case class Failure(cause: Any) extends Reason

  /**
   * This case object is received in case of a state timeout.
   */
  case object StateTimeout

  /**
   * INTERNAL API
   */
  private case class TimeoutMarker(generation: Long)

  /**
   * INTERNAL API
   */
  // FIXME: what about the cancellable?
  private[akka] case class Timer(name: String, msg: Any, repeat: Boolean, generation: Int)(context: ActorContext)
    extends NoSerializationVerificationNeeded {
    private var ref: Option[Cancellable] = _
    private val scheduler = context.system.scheduler
    private implicit val executionContext = context.dispatcher

    def schedule(actor: ActorRef, timeout: FiniteDuration): Unit =
      ref = Some(
        if (repeat) scheduler.schedule(timeout, timeout, actor, this)
        else scheduler.scheduleOnce(timeout, actor, this))

    def cancel(): Unit =
      if (ref.isDefined) {
        ref.get.cancel()
        ref = None
      }
  }

  /**
   * This extractor is just convenience for matching a (S, S) pair, including a
   * reminder what the new state is.
   */
  object -> {
    def unapply[S](in: (S, S)) = Some(in)
  }

  /**
   * Log Entry of the [[akka.actor.LoggingFSM]], can be obtained by calling `getLog`.
   */
  case class LogEntry[S, D](stateName: S, stateData: D, event: Any)

  /**
   * This captures all of the managed state of the [[akka.actor.FSM]]: the state
   * name, the state data, possibly custom timeout, stop reason and replies
   * accumulated while processing the last message.
   */
  case class State[S, D](stateName: S, stateData: D, timeout: Option[FiniteDuration] = None, stopReason: Option[Reason] = None, replies: List[Any] = Nil) {

    /**
     * Modify state transition descriptor to include a state timeout for the
     * next state. This timeout overrides any default timeout set for the next
     * state.
     *
     * Use Duration.Inf to deactivate an existing timeout.
     */
    def forMax(timeout: Duration): State[S, D] = timeout match {
      case f: FiniteDuration ⇒ copy(timeout = Some(f))
      case _                 ⇒ copy(timeout = None)
    }

    /**
     * Send reply to sender of the current message, if available.
     *
     * @return this state transition descriptor
     */
    def replying(replyValue: Any): State[S, D] = {
      copy(replies = replyValue :: replies)
    }

    /**
     * Modify state transition descriptor with new state data. The data will be
     * set when transitioning to the new state.
     */
    def using(nextStateDate: D): State[S, D] = {
      copy(stateData = nextStateDate)
    }

    /**
     * INTERNAL API.
     */
    private[akka] def withStopReason(reason: Reason): State[S, D] = {
      copy(stopReason = Some(reason))
    }
  }
  /**
   * All messages sent to the [[akka.actor.FSM]] will be wrapped inside an
   * `Event`, which allows pattern matching to extract both state and data.
   */
  case class Event[D](event: Any, stateData: D) extends NoSerializationVerificationNeeded

  /**
   * Case class representing the state of the [[akka.actor.FSM]] whithin the
   * `onTermination` block.
   */
  case class StopEvent[S, D](reason: Reason, currentState: S, stateData: D) extends NoSerializationVerificationNeeded

}

/**
 * Finite State Machine actor trait. Use as follows:
 *
 * <pre>
 *   object A {
 *     trait State
 *     case class One extends State
 *     case class Two extends State
 *
 *     case class Data(i : Int)
 *   }
 *
 *   class A extends Actor with FSM[A.State, A.Data] {
 *     import A._
 *
 *     startWith(One, Data(42))
 *     when(One) {
 *         case Event(SomeMsg, Data(x)) => ...
 *         case Ev(SomeMsg) => ... // convenience when data not needed
 *     }
 *     when(Two, stateTimeout = 5 seconds) { ... }
 *     initialize()
 *   }
 * </pre>
 *
 * Within the partial function the following values are returned for effecting
 * state transitions:
 *
 *  - <code>stay</code> for staying in the same state
 *  - <code>stay using Data(...)</code> for staying in the same state, but with
 *    different data
 *  - <code>stay forMax 5.millis</code> for staying with a state timeout; can be
 *    combined with <code>using</code>
 *  - <code>goto(...)</code> for changing into a different state; also supports
 *    <code>using</code> and <code>forMax</code>
 *  - <code>stop</code> for terminating this FSM actor
 *
 * Each of the above also supports the method <code>replying(AnyRef)</code> for
 * sending a reply before changing state.
 *
 * While changing state, custom handlers may be invoked which are registered
 * using <code>onTransition</code>. This is meant to enable concentrating
 * different concerns in different places; you may choose to use
 * <code>when</code> for describing the properties of a state, including of
 * course initiating transitions, but you can describe the transitions using
 * <code>onTransition</code> to avoid having to duplicate that code among
 * multiple paths which lead to a transition:
 *
 * <pre>
 * onTransition {
 *   case Active -&gt; _ =&gt; cancelTimer("activeTimer")
 * }
 * </pre>
 *
 * Multiple such blocks are supported and all of them will be called, not only
 * the first matching one.
 *
 * Another feature is that other actors may subscribe for transition events by
 * sending a <code>SubscribeTransitionCallback</code> message to this actor.
 * Stopping a listener without unregistering will not remove the listener from the
 * subscription list; use <code>UnsubscribeTransitionCallback</code> before stopping
 * the listener.
 *
 * State timeouts set an upper bound to the time which may pass before another
 * message is received in the current state. If no external message is
 * available, then upon expiry of the timeout a StateTimeout message is sent.
 * Note that this message will only be received in the state for which the
 * timeout was set and that any message received will cancel the timeout
 * (possibly to be started again by the next transition).
 *
 * Another feature is the ability to install and cancel single-shot as well as
 * repeated timers which arrange for the sending of a user-specified message:
 *
 * <pre>
 *   setTimer("tock", TockMsg, 1 second, true) // repeating
 *   setTimer("lifetime", TerminateMsg, 1 hour, false) // single-shot
 *   cancelTimer("tock")
 *   isTimerActive("tock")
 * </pre>
 */
trait FSM[S, D] extends Actor with Listeners with ActorLogging {

  import FSM._

  type State = FSM.State[S, D]
  type Event = FSM.Event[D]
  type StopEvent = FSM.StopEvent[S, D]
  type StateFunction = scala.PartialFunction[Event, State]
  type Timeout = Option[FiniteDuration]
  type TransitionHandler = PartialFunction[(S, S), Unit]

  /*
   * “import” so that these are visible without an import
   */
  val Event: FSM.Event.type = FSM.Event
  val StopEvent: FSM.StopEvent.type = FSM.StopEvent

  /**
   * This extractor is just convenience for matching a (S, S) pair, including a
   * reminder what the new state is.
   */
  val -> = FSM.->

  /**
   * This case object is received in case of a state timeout.
   */
  val StateTimeout = FSM.StateTimeout

  /**
   * ****************************************
   *                 DSL
   * ****************************************
   */

  /**
   * Insert a new StateFunction at the end of the processing chain for the
   * given state. If the stateTimeout parameter is set, entering this state
   * without a differing explicit timeout setting will trigger a StateTimeout
   * event; the same is true when using #stay.
   *
   * @param stateName designator for the state
   * @param stateTimeout default state timeout for this state
   * @param stateFunction partial function describing response to input
   */
  final def when(stateName: S, stateTimeout: FiniteDuration = null)(stateFunction: StateFunction): Unit =
    register(stateName, stateFunction, Option(stateTimeout))

  /**
   * Set initial state. Call this method from the constructor before the [[#initialize]] method.
   * If different state is needed after a restart this method, followed by [[#initialize]], can
   * be used in the actor life cycle hooks [[akka.actor.Actor#preStart]] and [[akka.actor.Actor#postRestart]].
   *
   * @param stateName initial state designator
   * @param stateData initial state data
   * @param timeout state timeout for the initial state, overriding the default timeout for that state
   */
  final def startWith(stateName: S, stateData: D, timeout: Timeout = None): Unit =
    currentState = FSM.State(stateName, stateData, timeout)

  /**
   * Produce transition to other state. Return this from a state function in
   * order to effect the transition.
   *
   * @param nextStateName state designator for the next state
   * @return state transition descriptor
   */
  final def goto(nextStateName: S): State = FSM.State(nextStateName, currentState.stateData)

  /**
   * Produce "empty" transition descriptor. Return this from a state function
   * when no state change is to be effected.
   *
   * @return descriptor for staying in current state
   */
  final def stay(): State = goto(currentState.stateName) // cannot directly use currentState because of the timeout field

  /**
   * Produce change descriptor to stop this FSM actor with reason "Normal".
   */
  final def stop(): State = stop(Normal)

  /**
   * Produce change descriptor to stop this FSM actor including specified reason.
   */
  final def stop(reason: Reason): State = stop(reason, currentState.stateData)

  /**
   * Produce change descriptor to stop this FSM actor including specified reason.
   */
  final def stop(reason: Reason, stateData: D): State = stay using stateData withStopReason (reason)

  final class TransformHelper(func: StateFunction) {
    def using(andThen: PartialFunction[State, State]): StateFunction =
      func andThen (andThen orElse { case x ⇒ x })
  }

  final def transform(func: StateFunction): TransformHelper = new TransformHelper(func)

  /**
   * Schedule named timer to deliver message after given delay, possibly repeating.
   * Any existing timer with the same name will automatically be canceled before
   * adding the new timer.
   * @param name identifier to be used with cancelTimer()
   * @param msg message to be delivered
   * @param timeout delay of first message delivery and between subsequent messages
   * @param repeat send once if false, scheduleAtFixedRate if true
   * @return current state descriptor
   */
  final def setTimer(name: String, msg: Any, timeout: FiniteDuration, repeat: Boolean = false): Unit = {
    if (debugEvent)
      log.debug("setting " + (if (repeat) "repeating " else "") + "timer '" + name + "'/" + timeout + ": " + msg)
    if (timers contains name) {
      timers(name).cancel
    }
    val timer = Timer(name, msg, repeat, timerGen.next)(context)
    timer.schedule(self, timeout)
    timers(name) = timer
  }

  /**
   * Cancel named timer, ensuring that the message is not subsequently delivered (no race).
   * @param name of the timer to cancel
   */
  final def cancelTimer(name: String): Unit = {
    if (debugEvent)
      log.debug("canceling timer '" + name + "'")
    if (timers contains name) {
      timers(name).cancel
      timers -= name
    }
  }

  /**
   * Inquire whether the named timer is still active. Returns true unless the
   * timer does not exist, has previously been canceled or if it was a
   * single-shot timer whose message was already received.
   */
  final def isTimerActive(name: String): Boolean = timers contains name

  /**
   * Set state timeout explicitly. This method can safely be used from within a
   * state handler.
   */
  final def setStateTimeout(state: S, timeout: Timeout): Unit = stateTimeouts(state) = timeout

  /**
   * INTERNAL API, used for testing.
   */
  private[akka] final def isStateTimerActive = timeoutFuture.isDefined

  /**
   * Set handler which is called upon each state transition, i.e. not when
   * staying in the same state. This may use the pair extractor defined in the
   * FSM companion object like so:
   *
   * <pre>
   * onTransition {
   *   case Old -&gt; New =&gt; doSomething
   * }
   * </pre>
   *
   * It is also possible to supply a 2-ary function object:
   *
   * <pre>
   * onTransition(handler _)
   *
   * private def handler(from: S, to: S) { ... }
   * </pre>
   *
   * The underscore is unfortunately necessary to enable the nicer syntax shown
   * above (it uses the implicit conversion total2pf under the hood).
   *
   * <b>Multiple handlers may be installed, and every one of them will be
   * called, not only the first one matching.</b>
   */
  final def onTransition(transitionHandler: TransitionHandler): Unit = transitionEvent :+= transitionHandler

  /**
   * Convenience wrapper for using a total function instead of a partial
   * function literal. To be used with onTransition.
   */
  implicit final def total2pf(transitionHandler: (S, S) ⇒ Unit): TransitionHandler =
    new TransitionHandler {
      def isDefinedAt(in: (S, S)) = true
      def apply(in: (S, S)) { transitionHandler(in._1, in._2) }
    }

  /**
   * Set handler which is called upon termination of this FSM actor. Calling
   * this method again will overwrite the previous contents.
   */
  final def onTermination(terminationHandler: PartialFunction[StopEvent, Unit]): Unit =
    terminateEvent = terminationHandler

  /**
   * Set handler which is called upon reception of unhandled messages. Calling
   * this method again will overwrite the previous contents.
   *
   * The current state may be queried using ``stateName``.
   */
  final def whenUnhandled(stateFunction: StateFunction): Unit =
    handleEvent = stateFunction orElse handleEventDefault

  /**
   * Verify existence of initial state and setup timers. This should be the
   * last call within the constructor, or [[akka.actor.Actor#preStart]] and
   * [[akka.actor.Actor#postRestart]]
   *
   * @see [[#startWith]]
   */
  final def initialize(): Unit = makeTransition(currentState)

  /**
   * Return current state name (i.e. object of type S)
   */
  final def stateName: S = currentState.stateName

  /**
   * Return current state data (i.e. object of type D)
   */
  final def stateData: D = currentState.stateData

  /**
   * Return next state data (available in onTransition handlers)
   */
  final def nextStateData = nextState match {
    case null ⇒ throw new IllegalStateException("nextStateData is only available during onTransition")
    case x    ⇒ x.stateData
  }

  /*
   * ****************************************************************
   *                PRIVATE IMPLEMENTATION DETAILS
   * ****************************************************************
   */

  private[akka] def debugEvent: Boolean = false

  /*
   * FSM State data and current timeout handling
   */
  private var currentState: State = _
  private var timeoutFuture: Option[Cancellable] = None
  private var nextState: State = _
  private var generation: Long = 0L

  /*
   * Timer handling
   */
  private val timers = mutable.Map[String, Timer]()
  private val timerGen = Iterator from 0

  /*
   * State definitions
   */
  private val stateFunctions = mutable.Map[S, StateFunction]()
  private val stateTimeouts = mutable.Map[S, Timeout]()

  private def register(name: S, function: StateFunction, timeout: Timeout): Unit = {
    if (stateFunctions contains name) {
      stateFunctions(name) = stateFunctions(name) orElse function
      stateTimeouts(name) = timeout orElse stateTimeouts(name)
    } else {
      stateFunctions(name) = function
      stateTimeouts(name) = timeout
    }
  }

  /*
   * unhandled event handler
   */
  private val handleEventDefault: StateFunction = {
    case Event(value, stateData) ⇒
      log.warning("unhandled event " + value + " in state " + stateName)
      stay
  }
  private var handleEvent: StateFunction = handleEventDefault

  /*
   * termination handling
   */
  private var terminateEvent: PartialFunction[StopEvent, Unit] = NullFunction

  /*
   * transition handling
   */
  private var transitionEvent: List[TransitionHandler] = Nil
  private def handleTransition(prev: S, next: S) {
    val tuple = (prev, next)
    for (te ← transitionEvent) { if (te.isDefinedAt(tuple)) te(tuple) }
  }

  /*
   * *******************************************
   *       Main actor receive() method
   * *******************************************
   */
  override def receive: Receive = {
    case TimeoutMarker(gen) ⇒
      if (generation == gen) {
        processMsg(StateTimeout, "state timeout")
      }
    case t @ Timer(name, msg, repeat, gen) ⇒
      if ((timers contains name) && (timers(name).generation == gen)) {
        if (timeoutFuture.isDefined) {
          timeoutFuture.get.cancel()
          timeoutFuture = None
        }
        generation += 1
        if (!repeat) {
          timers -= name
        }
        processMsg(msg, t)
      }
    case SubscribeTransitionCallBack(actorRef) ⇒
      // TODO Use context.watch(actor) and receive Terminated(actor) to clean up list
      listeners.add(actorRef)
      // send current state back as reference point
      actorRef ! CurrentState(self, currentState.stateName)
    case Listen(actorRef) ⇒
      // TODO Use context.watch(actor) and receive Terminated(actor) to clean up list
      listeners.add(actorRef)
      // send current state back as reference point
      actorRef ! CurrentState(self, currentState.stateName)
    case UnsubscribeTransitionCallBack(actorRef) ⇒
      listeners.remove(actorRef)
    case Deafen(actorRef) ⇒
      listeners.remove(actorRef)
    case value ⇒ {
      if (timeoutFuture.isDefined) {
        timeoutFuture.get.cancel()
        timeoutFuture = None
      }
      generation += 1
      processMsg(value, sender())
    }
  }

  private def processMsg(value: Any, source: AnyRef): Unit = {
    val event = Event(value, currentState.stateData)
    processEvent(event, source)
  }

  private[akka] def processEvent(event: Event, source: AnyRef): Unit = {
    val stateFunc = stateFunctions(currentState.stateName)
    val nextState = if (stateFunc isDefinedAt event) {
      stateFunc(event)
    } else {
      // handleEventDefault ensures that this is always defined
      handleEvent(event)
    }
    applyState(nextState)
  }

  private[akka] def applyState(nextState: State): Unit = {
    nextState.stopReason match {
      case None ⇒ makeTransition(nextState)
      case _ ⇒
        nextState.replies.reverse foreach { r ⇒ sender() ! r }
        terminate(nextState)
        context.stop(self)
    }
  }

  private[akka] def makeTransition(nextState: State): Unit = {
    if (!stateFunctions.contains(nextState.stateName)) {
      terminate(stay withStopReason Failure("Next state %s does not exist".format(nextState.stateName)))
    } else {
      nextState.replies.reverse foreach { r ⇒ sender() ! r }
      if (currentState.stateName != nextState.stateName) {
        this.nextState = nextState
        handleTransition(currentState.stateName, nextState.stateName)
        gossip(Transition(self, currentState.stateName, nextState.stateName))
        this.nextState = null
      }
      currentState = nextState
      val timeout = if (currentState.timeout.isDefined) currentState.timeout else stateTimeouts(currentState.stateName)
      if (timeout.isDefined) {
        val t = timeout.get
        if (t.isFinite && t.length >= 0) {
          import context.dispatcher
          timeoutFuture = Some(context.system.scheduler.scheduleOnce(t, self, TimeoutMarker(generation)))
        }
      }
    }
  }

  /**
   * Call `onTermination` hook; if you want to retain this behavior when
   * overriding make sure to call `super.postStop()`.
   *
   * Please note that this method is called by default from `preRestart()`,
   * so override that one if `onTermination` shall not be called during
   * restart.
   */
  override def postStop(): Unit = {
    /*
     * setting this instance’s state to terminated does no harm during restart
     * since the new instance will initialize fresh using startWith()
     */
    terminate(stay withStopReason Shutdown)
    super.postStop()
  }

  private def terminate(nextState: State): Unit = {
    if (currentState.stopReason.isEmpty) {
      val reason = nextState.stopReason.get
      logTermination(reason)
      for (timer ← timers.values) timer.cancel()
      timers.clear()
      currentState = nextState

      val stopEvent = StopEvent(reason, currentState.stateName, currentState.stateData)
      if (terminateEvent.isDefinedAt(stopEvent))
        terminateEvent(stopEvent)
    }
  }

  /**
   * By default [[FSM.Failure]] is logged at error level and other reason
   * types are not logged. It is possible to override this behavior.
   */
  protected def logTermination(reason: Reason): Unit = reason match {
    case Failure(ex: Throwable) ⇒ log.error(ex, "terminating due to Failure")
    case Failure(msg: AnyRef)   ⇒ log.error(msg.toString)
    case _                      ⇒
  }
}

/**
 * Stackable trait for [[akka.actor.FSM]] which adds a rolling event log and
 * debug logging capabilities (analogous to [[akka.event.LoggingReceive]]).
 *
 * @since 1.2
 */
trait LoggingFSM[S, D] extends FSM[S, D] { this: Actor ⇒

  import FSM._

  def logDepth: Int = 0

  private[akka] override val debugEvent = context.system.settings.FsmDebugEvent

  private val events = new Array[Event](logDepth)
  private val states = new Array[AnyRef](logDepth)
  private var pos = 0
  private var full = false

  private def advance() {
    val n = pos + 1
    if (n == logDepth) {
      full = true
      pos = 0
    } else {
      pos = n
    }
  }

  private[akka] abstract override def processEvent(event: Event, source: AnyRef): Unit = {
    if (debugEvent) {
      val srcstr = source match {
        case s: String            ⇒ s
        case Timer(name, _, _, _) ⇒ "timer " + name
        case a: ActorRef          ⇒ a.toString
        case _                    ⇒ "unknown"
      }
      log.debug("processing " + event + " from " + srcstr)
    }

    if (logDepth > 0) {
      states(pos) = stateName.asInstanceOf[AnyRef]
      events(pos) = event
      advance()
    }

    val oldState = stateName
    super.processEvent(event, source)
    val newState = stateName

    if (debugEvent && oldState != newState)
      log.debug("transition " + oldState + " -> " + newState)
  }

  /**
   * Retrieve current rolling log in oldest-first order. The log is filled with
   * each incoming event before processing by the user supplied state handler.
   * The log entries are lost when this actor is restarted.
   */
  protected def getLog: IndexedSeq[LogEntry[S, D]] = {
    val log = events zip states filter (_._1 ne null) map (x ⇒ LogEntry(x._2.asInstanceOf[S], x._1.stateData, x._1.event))
    if (full) {
      IndexedSeq() ++ log.drop(pos) ++ log.take(pos)
    } else {
      IndexedSeq() ++ log
    }
  }

}

/**
 * Java API: compatible with lambda expressions
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
object AbstractFSM {
  /**
   * A partial function value which does not match anything and can be used to
   * “reset” `whenUnhandled` and `onTermination` handlers.
   *
   * {{{
   * onTermination(FSM.NullFunction())
   * }}}
   */
  def NullFunction[S, D]: PartialFunction[S, D] = FSM.NullFunction
}

/** @note IMPLEMENT IN SCALA.JS
/**
 * Java API: compatible with lambda expressions
 *
 * Finite State Machine actor abstract base class.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractFSM[S, D] extends FSM[S, D] {
  import akka.japi.pf._
  import akka.japi.pf.FI._
  import java.util.{ List ⇒ JList }
  import FSM._

  /**
   * Insert a new StateFunction at the end of the processing chain for the
   * given state.
   *
   * @param stateName designator for the state
   * @param stateFunction partial function describing response to input
   */
  final def when(stateName: S)(stateFunction: StateFunction): Unit =
    when(stateName, null: FiniteDuration)(stateFunction)

  /**
   * Insert a new StateFunction at the end of the processing chain for the
   * given state.
   *
   * @param stateName designator for the state
   * @param stateFunctionBuilder partial function builder describing response to input
   */
  final def when(stateName: S, stateFunctionBuilder: FSMStateFunctionBuilder[S, D]): Unit =
    when(stateName, null, stateFunctionBuilder)

  /**
   * Insert a new StateFunction at the end of the processing chain for the
   * given state. If the stateTimeout parameter is set, entering this state
   * without a differing explicit timeout setting will trigger a StateTimeout
   * event; the same is true when using #stay.
   *
   * @param stateName designator for the state
   * @param stateTimeout default state timeout for this state
   * @param stateFunctionBuilder partial function builder describing response to input
   */
  final def when(stateName: S,
                 stateTimeout: FiniteDuration,
                 stateFunctionBuilder: FSMStateFunctionBuilder[S, D]): Unit =
    when(stateName, stateTimeout)(stateFunctionBuilder.build())

  /**
   * Set initial state. Call this method from the constructor before the [[#initialize]] method.
   * If different state is needed after a restart this method, followed by [[#initialize]], can
   * be used in the actor life cycle hooks [[akka.actor.Actor#preStart]] and [[akka.actor.Actor#postRestart]].
   *
   * @param stateName initial state designator
   * @param stateData initial state data
   */
  final def startWith(stateName: S, stateData: D): Unit =
    startWith(stateName, stateData, null: FiniteDuration)

  /**
   * Set initial state. Call this method from the constructor before the [[#initialize]] method.
   * If different state is needed after a restart this method, followed by [[#initialize]], can
   * be used in the actor life cycle hooks [[akka.actor.Actor#preStart]] and [[akka.actor.Actor#postRestart]].
   *
   * @param stateName initial state designator
   * @param stateData initial state data
   * @param timeout state timeout for the initial state, overriding the default timeout for that state
   */
  final def startWith(stateName: S, stateData: D, timeout: FiniteDuration): Unit =
    startWith(stateName, stateData, Option(timeout))

  /**
   * Add a handler which is called upon each state transition, i.e. not when
   * staying in the same state.
   *
   * <b>Multiple handlers may be installed, and every one of them will be
   * called, not only the first one matching.</b>
   */
  final def onTransition(transitionHandlerBuilder: FSMTransitionHandlerBuilder[S]): Unit =
    onTransition(transitionHandlerBuilder.build().asInstanceOf[TransitionHandler])

  /**
   * Add a handler which is called upon each state transition, i.e. not when
   * staying in the same state.
   *
   * <b>Multiple handlers may be installed, and every one of them will be
   * called, not only the first one matching.</b>
   */
  final def onTransition(transitionHandler: UnitApply2[S, S]): Unit =
    onTransition(transitionHandler)

  /**
   * Set handler which is called upon reception of unhandled messages. Calling
   * this method again will overwrite the previous contents.
   *
   * The current state may be queried using ``stateName``.
   */
  final def whenUnhandled(stateFunctionBuilder: FSMStateFunctionBuilder[S, D]): Unit =
    whenUnhandled(stateFunctionBuilder.build())

  /**
   * Set handler which is called upon termination of this FSM actor. Calling
   * this method again will overwrite the previous contents.
   */
  final def onTermination(stopBuilder: FSMStopBuilder[S, D]): Unit =
    onTermination(stopBuilder.build().asInstanceOf[PartialFunction[StopEvent, Unit]])

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches on an event and data type and a predicate.
   *
   * @param eventType  the event type to match on
   * @param dataType  the data type to match on
   * @param predicate  a predicate to evaluate on the matched types
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent[ET, DT <: D](eventType: Class[ET], dataType: Class[DT], predicate: TypedPredicate2[ET, DT], apply: Apply2[ET, DT, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventType, dataType, predicate, apply)

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches on an event and data type.
   *
   * @param eventType  the event type to match on
   * @param dataType  the data type to match on
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent[ET, DT <: D](eventType: Class[ET], dataType: Class[DT], apply: Apply2[ET, DT, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventType, dataType, apply)

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches if the event type and predicate matches.
   *
   * @param eventType  the event type to match on
   * @param predicate  a predicate that will be evaluated on the data and the event
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent[ET](eventType: Class[ET], predicate: TypedPredicate2[ET, D], apply: Apply2[ET, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventType, predicate, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches if the event type matches.
   *
   * @param eventType  the event type to match on
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent[ET](eventType: Class[ET], apply: Apply2[ET, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventType, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches if the predicate matches.
   *
   * @param predicate  a predicate that will be evaluated on the data and the event
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent(predicate: TypedPredicate2[AnyRef, D], apply: Apply2[AnyRef, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(predicate, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches on the data type and if any of the event types
   * in the list match or any of the event instances in the list compares equal.
   *
   * @param eventMatches  a list of types or instances to match against
   * @param dataType  the data type to match on
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent[DT <: D](eventMatches: JList[AnyRef], dataType: Class[DT], apply: Apply2[AnyRef, DT, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventMatches, dataType, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches if any of the event types in the list match or any
   * of the event instances in the list compares equal.
   *
   * @param eventMatches  a list of types or instances to match against
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent(eventMatches: JList[AnyRef], apply: Apply2[AnyRef, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventMatches, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches on the data type and if the event compares equal.
   *
   * @param event  an event to compare equal against
   * @param dataType  the data type to match on
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEventEquals[E, DT <: D](event: E, dataType: Class[DT], apply: Apply2[E, DT, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().eventEquals(event, dataType, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches if the event compares equal.
   *
   * @param event  an event to compare equal against
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEventEquals[E](event: E, apply: Apply2[E, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().eventEquals(event, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches on any type of event.
   *
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchAnyEvent(apply: Apply2[AnyRef, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().anyEvent(apply)

  /**
   * Create an [[akka.japi.pf.FSMTransitionHandlerBuilder]] with the first case statement set.
   *
   * A case statement that matches on a from state and a to state.
   *
   * @param fromState  the from state to match on
   * @param toState  the to state to match on
   * @param apply  an action to apply when the states match
   * @return the builder with the case statement added
   */
  final def matchState(fromState: S, toState: S, apply: UnitApplyVoid): FSMTransitionHandlerBuilder[S] =
    new FSMTransitionHandlerBuilder[S]().state(fromState, toState, apply)

  /**
   * Create an [[akka.japi.pf.FSMTransitionHandlerBuilder]] with the first case statement set.
   *
   * A case statement that matches on a from state and a to state.
   *
   * @param fromState  the from state to match on
   * @param toState  the to state to match on
   * @param apply  an action to apply when the states match
   * @return the builder with the case statement added
   */
  final def matchState(fromState: S, toState: S, apply: UnitApply2[S, S]): FSMTransitionHandlerBuilder[S] =
    new FSMTransitionHandlerBuilder[S]().state(fromState, toState, apply)

  /**
   * Create an [[akka.japi.pf.FSMStopBuilder]] with the first case statement set.
   *
   * A case statement that matches on an [[FSM.Reason]].
   *
   * @param reason  the reason for the termination
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchStop(reason: Reason, apply: UnitApply2[S, D]): FSMStopBuilder[S, D] =
    new FSMStopBuilder[S, D]().stop(reason, apply)

  /**
   * Create an [[akka.japi.pf.FSMStopBuilder]] with the first case statement set.
   *
   * A case statement that matches on a reason type.
   *
   * @param reasonType  the reason type to match on
   * @param apply  an action to apply to the reason, event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchStop[RT <: Reason](reasonType: Class[RT], apply: UnitApply3[RT, S, D]): FSMStopBuilder[S, D] =
    new FSMStopBuilder[S, D]().stop(reasonType, apply)

  /**
   * Create an [[akka.japi.pf.FSMStopBuilder]] with the first case statement set.
   *
   * A case statement that matches on a reason type and a predicate.
   *
   * @param reasonType  the reason type to match on
   * @param apply  an action to apply to the reason, event and state data if there is a match
   * @param predicate  a predicate that will be evaluated on the reason if the type matches
   * @return the builder with the case statement added
   */
  final def matchStop[RT <: Reason](reasonType: Class[RT], predicate: TypedPredicate[RT], apply: UnitApply3[RT, S, D]): FSMStopBuilder[S, D] =
    new FSMStopBuilder[S, D]().stop(reasonType, predicate, apply)

  /**
   * Create a [[akka.japi.pf.UnitPFBuilder]] with the first case statement set.
   *
   * @param dataType  a type to match the argument against
   * @param apply  an action to apply to the argument if the type matches
   * @return a builder with the case statement added
   */
  final def matchData[DT <: D](dataType: Class[DT], apply: UnitApply[DT]): UnitPFBuilder[D] =
    UnitMatch.`match`(dataType, apply)

  /**
   * Create a [[akka.japi.pf.UnitPFBuilder]] with the first case statement set.
   *
   * @param dataType  a type to match the argument against
   * @param predicate  a predicate that will be evaluated on the argument if the type matches
   * @param apply  an action to apply to the argument if the type and predicate matches
   * @return a builder with the case statement added
   */
  final def matchData[DT <: D](dataType: Class[DT], predicate: TypedPredicate[DT], apply: UnitApply[DT]): UnitPFBuilder[D] =
    UnitMatch.`match`(dataType, predicate, apply)

  /**
   * Produce transition to other state. Return this from a state function in
   * order to effect the transition.
   *
   * @param nextStateName state designator for the next state
   * @return state transition descriptor
   */
  final def goTo(nextStateName: S): State = goto(nextStateName)

  /**
   * Schedule named timer to deliver message after given delay, possibly repeating.
   * Any existing timer with the same name will automatically be canceled before
   * adding the new timer.
   * @param name identifier to be used with cancelTimer()
   * @param msg message to be delivered
   * @param timeout delay of first message delivery and between subsequent messages
   * @return current state descriptor
   */
  final def setTimer(name: String, msg: Any, timeout: FiniteDuration): Unit =
    setTimer(name, msg, timeout, false);

  /**
   * Default reason if calling `stop()`.
   */
  val Normal: FSM.Reason = FSM.Normal

  /**
   * Reason given when someone was calling `system.stop(fsm)` from outside;
   * also applies to `Stop` supervision directive.
   */
  val Shutdown: FSM.Reason = FSM.Shutdown
}

/**
 * Java API: compatible with lambda expressions
 *
 * Finite State Machine actor abstract base class.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractLoggingFSM[S, D] extends AbstractFSM[S, D] with LoggingFSM[S, D]
*/
