import java.lang.reflect.*;
import java.net.*;
import java.io.*;
import java.util.*;

/** Bounded alias-preserving graph search prototype. Not a production resolver. */
public final class AliasProbe {
    static final class Route {
        final List<Field> fields;
        Route(List<Field> fields) { this.fields = fields; }
        String label() {
            StringBuilder b=new StringBuilder("MIDlet");
            for(Field f:fields)b.append('.').append(f.getName());
            return b.toString();
        }
        Object follow(Object root) throws Exception {
            Object o=root;for(Field f:fields){if(o==null)return null;o=f.get(o);}return o;
        }
    }
    static final class Node {
        final Object value;
        final List<Field> path;
        final IdentityHashMap<Object,Boolean> ancestors;
        Node(Object value,List<Field> path,IdentityHashMap<Object,Boolean> ancestors){
            this.value=value;this.path=path;this.ancestors=ancestors;
        }
    }
    static List<Route> find(Object root,Object target,int nodesMax,int depthMax,int pathsMax)
        throws Exception {
        ArrayDeque<Node> q=new ArrayDeque<>();
        q.add(new Node(root,new ArrayList<>(),new IdentityHashMap<>()));
        List<Route> result=new ArrayList<>();int visited=0;
        while(!q.isEmpty()){
            Node n=q.removeFirst();
            if(++visited>nodesMax)throw new IllegalStateException("Traversal budget exhausted");
            if(n.value==target){
                result.add(new Route(n.path));
                if(result.size()>=pathsMax)break;
                continue;
            }
            if(n.path.size()>=depthMax||n.ancestors.containsKey(n.value))continue;
            IdentityHashMap<Object,Boolean> ancestors=new IdentityHashMap<>(n.ancestors);
            ancestors.put(n.value,true);
            for(Field f:n.value.getClass().getDeclaredFields()){
                if(Modifier.isStatic(f.getModifiers())||f.getType().isPrimitive()||f.isSynthetic())continue;
                f.setAccessible(true);
                Object child=f.get(n.value);
                if(child==null||ancestors.containsKey(child))continue;
                List<Field> path=new ArrayList<>(n.path);path.add(f);
                q.addLast(new Node(child,path,ancestors));
            }
        }
        return result;
    }
    public static void main(String[] args)throws Exception{
        URL jar=new File(args[0]).toURI().toURL();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{jar},null)){
            Class<?> type=loader.loadClass("rndguest.AliasGame");
            Object game=type.getConstructor().newInstance();
            Object actual=type.getField("actual").get(game);
            List<Route> routes=find(game,actual,50,8,8);
            if(routes.size()!=3)throw new AssertionError("Expected 3 routes, got "+routes.size());
            type.getMethod("shiftFirstAlias").invoke(game);
            int survived=0,rebound=0;
            for(Route route:routes){
                boolean unchanged=route.follow(game)==actual;
                if(unchanged)survived++;else rebound++;
                System.out.println(route.label()+" => "+(unchanged?"same object":"rebound"));
            }
            if(survived!=2||rebound!=1)throw new AssertionError("Expected 2 surviving aliases");
            boolean budgetObserved=false;
            try{find(game,actual,1,8,8);}
            catch(IllegalStateException e){budgetObserved=e.getMessage().contains("budget");}
            if(!budgetObserved)throw new AssertionError("Budget enforcement missing");
        }
        System.out.println("PASS alias_count=3 survived=2 rebound=1 budget=ENFORCED");
    }
}
