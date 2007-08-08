package org.lastbamboo.common.ice.candidate;

import org.lastbamboo.common.ice.IceStunChecker;



/**
 * Interface for a pair of ICE candidates.
 */
public interface IceCandidatePair extends Comparable
    {
    
    /**
     * Access the STUN connectivity checker for this pair.  Each pair has
     * its own connectivity checker bound between the local and remote hosts.
     * 
     * @return The ICE STUN connectivity checker for this pair.
     */
    IceStunChecker getConnectivityChecker();

    /**
     * Accessor for the local candidate for the pair.
     * 
     * @return The local candidiate for the pair.
     */
    IceCandidate getLocalCandidate();
    
    /**
     * Accessor for the remote candidate for the pair.
     * 
     * @return the remote candidate for the pair.
     */
    IceCandidate getRemoteCandidate();

    /**
     * Accessor for the priority for the pair.
     * 
     * @return The priority for the pair.
     */
    long getPriority();
    
    /**
     * Accesses the state of the pair.
     * 
     * @return The state of the pair.
     */
    IceCandidatePairState getState();
    
    /**
     * Accessor for the foundation for the pair.
     * 
     * @return The foundation for the candidate pair.  Note that this is a 
     * string because the foundation for the pair is the *concatenation* of
     * the foundations of the candidates.
     */
    String getFoundation();

    /**
     * Sets the state of the pair.
     * 
     * @param state The state of the pair.
     */
    void setState(IceCandidatePairState state);

    /**
     * Accessor for the component ID for the pair.  Note that both candidates
     * in the pair always have the same component ID.
     * 
     * @return The component ID for the pair.
     */
    int getComponentId();
    
    /**
     * Accepts the specified visitor to an ICE candidate pair.
     * 
     * @param <T> The class to return.
     * @param visitor The visitor to accept.
     * @return The class the visitor created. 
     */
    <T> T accept(IceCandidatePairVisitor<T> visitor);

    /**
     * Nominates this pair as potentially the final pair for exchanging media.  
     * The nominated pair with the highest priority is the pair that is 
     * ultimately used.
     * 
     * @param nominated Whether or not this pair is nominated as the final 
     * pair for exchanging media.
     */
    void nominate();

    /**
     * Recomputes the priority for the pair.
     */
    void recomputePriority();

    /**
     * Cancels the existing STUN transaction.  The behavior for this is 
     * described in ICE section 7.2.1.4. on triggered checks.  From that 
     * section, cancellation:<p> 
     * 
     * "means that the agent will not retransmit the 
     * request, will not treat the lack of response to be a failure, but will 
     * wait the duration of the transaction timeout for a response."
     */
    void cancelStunTransaction();

    /**
     * Tells the pair to set its nominated flag if a response arrives that
     * produces a successful result.  If no such response arrives, the nominated
     * flag does not change.
     */
    void nominateOnSuccess();

    /**
     * Returns whether or not this pair should be automatically nominated if it
     * results in successful response. The default is false, but this can
     * change for controlled candidates.  See:
     * 
     * http://tools.ietf.org/html/draft-ietf-mmusic-ice-17#section-7.2.1.5
     * 
     * @return Whether or not to automatically nominate this pair if it results
     * in a successful response.
     */
    boolean shouldNominateOnSuccess();

    }
