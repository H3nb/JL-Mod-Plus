import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/** Test-only bounded multi-parent predecessor graph. Not a production implementation. */
public final class DagProbe {
    static final class Edge {
        final Object parent; final Field field;
        Edge(Object parent, Field field) { this.parent=parent; this.field=field; }
    }
    static final class Node {
        final int depth;
        final List<Edge> incoming=new ArrayList<>();
        Node(int depth) {this.depth=depth;}
    }
    static final class Result {
        int expanded, edges;
        final List<String> paths=new ArrayList<>();
    }
    static Result find(Object root,Object target,int maxNodes,int maxEdges,
                       int maxParents,int maxDepth,int maxPaths) throws Exception {
        IdentityHashMap<Object,Node> visited=new IdentityHashMap<>();
        ArrayDeque<Object> queue=new ArrayDeque<>();
        visited.put(root,new Node(0)); queue.add(root);
        Result result=new Result();
        while(!queue.isEmpty()) {
            Object current=queue.removeFirst();
            Node info=visited.get(current);
            if(++result.expanded>maxNodes)throw new IllegalStateException("Node budget exceeded");
            if(info.depth>=maxDepth)continue;
            Class<?> type=current.getClass();
            if(!type.getName().startsWith("rndguest."))continue;
            for(Field field:type.getDeclaredFields()) {
                if(field.isSynthetic() || field.getType().isPrimitive()
                    || Modifier.isStatic(field.getModifiers()))continue;
                field.setAccessible(true);
                Object child=field.get(current);
                if(child==null)continue;
                if(++result.edges>maxEdges)throw new IllegalStateException("Edge budget exceeded");
                Node next=visited.get(child);
                if(next==null) {
                    next=new Node(info.depth+1);
                    visited.put(child,next);
                    queue.add(child);
                }
                // IMPORTANT: retain longer incoming alternatives, not only shortest-path edges.
                if(next.incoming.size()<maxParents)next.incoming.add(new Edge(current,field));
            }
        }
        if(visited.containsKey(target))paths(target,root,visited,new ArrayList<>(),
            new IdentityHashMap<>(),result.paths,maxPaths,maxDepth);
        return result;
    }
    static void paths(Object target,Object root,IdentityHashMap<Object,Node> graph,
                      List<String> reversed,IdentityHashMap<Object,Boolean> ancestors,
                      List<String> output,int maxPaths,int maxDepth) {
        if(output.size()>=maxPaths || reversed.size()>maxDepth || ancestors.containsKey(target))return;
        ancestors.put(target,Boolean.TRUE);
        if(target==root) {
            StringBuilder label=new StringBuilder("MIDlet");
            for(int index=reversed.size()-1;index>=0;index--)label.append('.').append(reversed.get(index));
            output.add(label.toString());
        } else {
            for(Edge edge:graph.get(target).incoming) {
                reversed.add(edge.field.getName());
                paths(edge.parent,root,graph,reversed,ancestors,output,maxPaths,maxDepth);
                reversed.remove(reversed.size()-1);
            }
        }
        ancestors.remove(target);
    }
    public static void main(String[] args)throws Exception {
        URL jar=new File(args[0]).toURI().toURL();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{jar},null)) {
            Class<?> gameType=loader.loadClass("rndguest.AliasGame");
            Object game=gameType.getConstructor().newInstance();
            Object target=gameType.getField("actual").get(game);
            Result original=find(game,target,50,100,4,8,8);
            if(original.paths.size()!=3)throw new AssertionError("Expected three routes: "+original.paths);
            gameType.getMethod("shiftFirstAlias").invoke(game);
            Result changed=find(game,target,50,100,4,8,8);
            if(changed.paths.size()!=2)throw new AssertionError("Expected two routes: "+changed.paths);
            boolean failed=false;
            try{find(game,target,1,100,4,8,8);}
            catch(IllegalStateException expected){failed=true;}
            if(!failed)throw new AssertionError("Missing traversal limit");
            System.out.println("PASS multi-parent graph routes=3->2 nodes="+changed.expanded
                +" edges="+changed.edges+" bound=ENFORCED");
        }
    }
}
