package io.dinzai.starforce;

/** Exact supported US dumps. Storage names preserve older transformation-source imports. */
final class GameProfile {
    static final String EXTRA_GAME="game";
    static final GameProfile LEO=new GameProfile("leo","Leo","leo","A6BE","defbc6f639fb0bcd11a229d1a0c7c9f595447034ae25d3ba4a97e1268156c5d6");
    static final GameProfile ZERKER_NINJA=new GameProfile("zerker-ninja","Zerker × Ninja","sf2","YRVE","87e960af99c8b9f0978751a3b22f17009aa370dddf43d76337dd244288b97cda");
    static final GameProfile BLACK_ACE=new GameProfile("black-ace","Black Ace","sf3","CRBE","543e65608eb8ce68d5ddecd4cd9f6ab0a479262823e5a0be25083982c4acfa43");
    static final GameProfile[] ALL={LEO,ZERKER_NINJA,BLACK_ACE};
    final String id,label,directory,code,sha256;
    final long size=33554432;
    private GameProfile(String id,String label,String directory,String code,String hash){this.id=id;this.label=label;this.directory=directory;this.code=code;this.sha256=hash;}
    static GameProfile fromId(String id){for(GameProfile g:ALL)if(g.id.equals(id))return g;throw new IllegalArgumentException("Unsupported game: "+id);}
    static GameProfile forSelection(int index){switch(index){case 0:return LEO;case 3:case 4:return ZERKER_NINJA;case 6:return BLACK_ACE;default:return null;}}
    boolean matches(long bytes,String gameCode,String hash){return bytes==size&&code.equals(gameCode)&&sha256.equals(hash);}
}
